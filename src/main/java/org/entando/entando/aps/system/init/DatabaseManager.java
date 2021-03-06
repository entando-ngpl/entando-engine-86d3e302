/*
 * Copyright 2015-Present Entando S.r.l. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */
package org.entando.entando.aps.system.init;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.sql.DataSource;

import com.agiletec.aps.system.ApsSystemUtils;
import org.entando.entando.aps.system.services.storage.StorageManagerUtil;
import org.entando.entando.ent.exception.EntException;
import com.agiletec.aps.util.ApsWebApplicationUtils;
import com.agiletec.aps.util.DateConverter;
import com.agiletec.aps.util.FileTextReader;
import org.apache.commons.beanutils.BeanComparator;
import org.entando.entando.aps.system.init.model.Component;
import org.entando.entando.aps.system.init.model.ComponentEnvironment;
import org.entando.entando.aps.system.init.model.ComponentInstallationReport;
import org.entando.entando.aps.system.init.model.ComponentUninstallerInfo;
import org.entando.entando.aps.system.init.model.DataInstallationReport;
import org.entando.entando.aps.system.init.model.DataSourceDumpReport;
import org.entando.entando.aps.system.init.model.DataSourceInstallationReport;
import org.entando.entando.aps.system.init.model.SystemInstallationReport;
import org.entando.entando.aps.system.init.util.TableDataUtils;
import org.entando.entando.aps.system.init.util.TableFactory;
import org.entando.entando.aps.system.services.storage.IStorageManager;
import org.entando.entando.ent.exception.EntRuntimeException;
import org.entando.entando.ent.util.EntLogging.EntLogger;
import org.entando.entando.ent.util.EntLogging.EntLogFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.io.Resource;
import org.springframework.web.context.ServletContextAware;

/**
 * @author E.Santoboni
 */
public class DatabaseManager extends AbstractInitializerManager
        implements IDatabaseManager, IDatabaseInstallerManager, ServletContextAware {

    private static final EntLogger logger = EntLogFactory.getSanitizedLogger(DatabaseManager.class);

    private static final String LOG_PREFIX = "|   ";

    public static final int STATUS_READY = 0;
    public static final int STATUS_DUMPING_IN_PROGRESS = 1;
    public static final String INIT_MSG_P = "+ [ Component: {} ] :: DATA\n{}";

    private Map<String, List<String>> entandoTableMapping;
    private Map<String, Resource> entandoDefaultSqlResources;
    private Map<String, Resource> testSqlResources;
    private Map<String, Resource> defaultSqlDump;
    private int status;

    private DatabaseDumper databaseDumper;
    private DatabaseRestorer databaseRestorer;

    private ServletContext servletContext;

    public void init() throws Exception {
        logger.debug("{} ready", this.getClass().getName());
    }

    @Override
    public SystemInstallationReport installDatabase(SystemInstallationReport report, boolean checkOnStatup) throws Exception {
        String lastLocalBackupFolder = null;
        if (null == report) {
            report = SystemInstallationReport.getInstance();
            if (checkOnStatup && !Environment.test.equals(this.getEnvironment())) {
                //non c'?? db locale installato, cerca nei backup locali
                DataSourceDumpReport lastDumpReport = this.getLastDumpReport();
                if (null != lastDumpReport) {
                    lastLocalBackupFolder = lastDumpReport.getSubFolderName();
                    report.setStatus(SystemInstallationReport.Status.RESTORE);
                } else {
                    //SE NON c'?? cerca il default dump
                    Map<String, Resource> sqlDump = this.getDefaultSqlDump();
                    if (null != sqlDump && sqlDump.size() > 0) {
                        report.setStatus(SystemInstallationReport.Status.RESTORE);
                    }
                }
            }
        }
        try {
            this.initMasterDatabases(report, checkOnStatup);
            List<Component> components = this.getComponentManager().getCurrentComponents();
            for (Component entandoComponentConfiguration : components) {
                this.initComponentDatabases(entandoComponentConfiguration, report, checkOnStatup);
            }
            this.initMasterDefaultResource(report, checkOnStatup);
            for (Component entandoComponentConfiguration : components) {
                this.initComponentDefaultResources(entandoComponentConfiguration, report, checkOnStatup);
            }
            if (checkOnStatup && report.getStatus().equals(SystemInstallationReport.Status.RESTORE)) {
                //ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH:MI:SS.FF'
                if (null != lastLocalBackupFolder) {
                    this.restoreBackup(lastLocalBackupFolder);
                } else {
                    this.restoreDefaultDump();
                }
            }
        } catch (Throwable t) {
            if (null != report && report.isUpdated()) {
                report.setUpdated();
                report.setStatus(SystemInstallationReport.Status.INCOMPLETE);
            }
            logger.error("Error while initializating Db Installer", t);
            throw new Exception("Error while initializating Db Installer", t);
        }
        return report;
    }

    private void initMasterDatabases(SystemInstallationReport report, boolean checkOnStatup) throws EntException {
        logger.info(INIT_MSG_P, "Core", LOG_PREFIX);
        ComponentInstallationReport componentReport = report.getComponentReport("entandoCore", true);
        DataSourceInstallationReport dataSourceReport = componentReport.getDataSourceReport();
        if (componentReport.getStatus().equals(SystemInstallationReport.Status.OK)) {
            logger.debug("{}( ok )  Already installed\n{}", LOG_PREFIX, LOG_PREFIX);
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( ok )  Already installed\n" + LOG_PREFIX);
            return;
        }
        try {
            String[] dataSourceNames = this.extractBeanNames(DataSource.class);
            Map<String, SystemInstallationReport.Status> databasesStatus = dataSourceReport.getDatabaseStatus();
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "Starting installation");
            for (String dataSourceName : dataSourceNames) {
                if (report.getStatus().equals(SystemInstallationReport.Status.PORTING)) {
                    ApsSystemUtils.directStdoutTrace(LOG_PREFIX + " - Already present! db " + dataSourceName);
                    SystemInstallationReport.Status status = (checkOnStatup)
                            ? report.getStatus()
                            : SystemInstallationReport.Status.SKIPPED;
                    databasesStatus.put(dataSourceName, status);
                    report.setUpdated();
                    continue;
                }
                SystemInstallationReport.Status dbStatus = databasesStatus.get(dataSourceName);
                if (dbStatus != null && (SystemInstallationReport.isSafeStatus(dbStatus))) {
                    ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "\n" + LOG_PREFIX + "( ok )  " + dataSourceName + " already installed");
                } else if (dbStatus == null || !dbStatus.equals(SystemInstallationReport.Status.OK)) {
                    DataSource dataSource = (DataSource) this.getBeanFactory().getBean(dataSourceName);
                    if (checkOnStatup) {
                        databasesStatus.put(dataSourceName, SystemInstallationReport.Status.INCOMPLETE);
                        ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "");
                        this.initMasterDatabase(dataSourceName, dataSource, dataSourceReport);
                        databasesStatus.put(dataSourceName, SystemInstallationReport.Status.OK);
                    } else {
                        databasesStatus.put(dataSourceName, SystemInstallationReport.Status.SKIPPED);
                    }
                    report.setUpdated();
                }
            }
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "\n" + LOG_PREFIX + "Installation complete\n" + LOG_PREFIX);
            logger.debug(LOG_PREFIX + "\n" + LOG_PREFIX + "Installation complete\n" + LOG_PREFIX);
        } catch (Throwable t) {
            logger.error("Error initializating master databases", t);
            throw new EntException("Error initializating master databases", t);
        }
    }

    private void initMasterDatabase(String databaseName, DataSource dataSource, DataSourceInstallationReport schemaReport) throws EntException {
        try {
            DatabaseType type = this.getDatabaseRestorer().getType(dataSource);
            if (type.equals(DatabaseType.DERBY)) {
                this.getDatabaseRestorer().initDerbySchema(dataSource);
            }
            List<String> tableClassNames = this.getEntandoTableMapping().get(databaseName);
            if (null == tableClassNames || tableClassNames.isEmpty()) {
                logger.debug("No Master Tables defined for db {}", databaseName);
                schemaReport.getDatabaseStatus().put(databaseName, SystemInstallationReport.Status.NOT_AVAILABLE);
            } else {
                this.createTables(databaseName, tableClassNames, dataSource, schemaReport);
            }
        } catch (Throwable t) {
            schemaReport.getDatabaseStatus().put(databaseName, SystemInstallationReport.Status.INCOMPLETE);
            logger.error("Error creating master tables to db {}", databaseName, t);
            throw new EntException("Error creating master tables to db " + databaseName, t);
        }
    }

    public void initComponentDatabases(Component componentConfiguration, SystemInstallationReport report, boolean checkOnStatup) throws EntException {
        logger.info(INIT_MSG_P, componentConfiguration.getCode(), LOG_PREFIX);
        ComponentInstallationReport componentReport = report.getComponentReport(componentConfiguration.getCode(), true);
        if (componentReport.getStatus().equals(SystemInstallationReport.Status.OK)) {
            logger.debug(LOG_PREFIX + "( ok )  Already installed\n" + LOG_PREFIX);
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( ok )  Already installed\n" + LOG_PREFIX);
            return;
        } else if (componentReport.getStatus().equals(SystemInstallationReport.Status.UNINSTALLED)) {
            logger.debug(LOG_PREFIX + "( ok )  Uninstalled\n" + LOG_PREFIX);
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( ok )  Uninstalled\n" + LOG_PREFIX);
            return;
        }
        try {
            String[] dataSourceNames = this.extractBeanNames(DataSource.class);
            Map<String, List<String>> tableMapping = componentConfiguration.getTableMapping();
            DataSourceInstallationReport dataSourceReport = componentReport.getDataSourceReport();
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "Starting installation\n" + LOG_PREFIX);
            for (String dataSourceName : dataSourceNames) {
                List<String> tableClassNames = (null != tableMapping) ? tableMapping.get(dataSourceName) : null;
                if (null == tableClassNames || tableClassNames.isEmpty()) {
                    ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( !! )  skipping " + dataSourceName + ": not available");
                    dataSourceReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.NOT_AVAILABLE);
                    report.setUpdated();
                    continue;
                }
                if (report.getStatus().equals(SystemInstallationReport.Status.PORTING)) {
                    SystemInstallationReport.Status status = (checkOnStatup)
                            ? report.getStatus()
                            : SystemInstallationReport.Status.SKIPPED;
                    dataSourceReport.getDatabaseStatus().put(dataSourceName, status);
                    logger.debug(LOG_PREFIX + "( ok )  {} already installed {}", dataSourceName, SystemInstallationReport.Status.PORTING);
                    ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( ok )  " + dataSourceName + " already installed" + SystemInstallationReport.Status.PORTING);
                    continue;
                }
                SystemInstallationReport.Status schemaStatus = dataSourceReport.getDatabaseStatus().get(dataSourceName);
                if (SystemInstallationReport.isSafeStatus(schemaStatus)) {
                    //Already Done!
                    ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( ok )  " + dataSourceName + " already installed" + SystemInstallationReport.Status.PORTING);
                    continue;
                }
                if (null == dataSourceReport.getDataSourceTables().get(dataSourceName)) {
                    dataSourceReport.getDataSourceTables().put(dataSourceName, new ArrayList<String>());
                }
                if (checkOnStatup) {
                    dataSourceReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.INCOMPLETE);
                    DataSource dataSource = (DataSource) this.getBeanFactory().getBean(dataSourceName);
                    this.createTables(dataSourceName, tableClassNames, dataSource, dataSourceReport);
                    ApsSystemUtils.directStdoutTrace(LOG_PREFIX);
                    dataSourceReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.OK);
                } else {
                    dataSourceReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.SKIPPED);
                }
                report.setUpdated();
            }
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "\n" + LOG_PREFIX + "Installation complete\n" + LOG_PREFIX);
            logger.debug(LOG_PREFIX + "\n" + LOG_PREFIX + "Installation complete\n" + LOG_PREFIX);
        } catch (Throwable t) {
            logger.error("Error initializating component {}", componentConfiguration.getCode(), t);
            throw new EntException("Error initializating component " + componentConfiguration.getCode(), t);
        }
    }

    private void createTables(String databaseName, List<String> tableClassNames,
            DataSource dataSource, DataSourceInstallationReport schemaReport) throws EntException {
        try {
            DatabaseType type = this.getDatabaseRestorer().getType(dataSource);
            TableFactory tableFactory = new TableFactory(databaseName, dataSource, type);
            tableFactory.createTables(tableClassNames, schemaReport);
        } catch (Throwable t) {
            logger.error("Error creating tables to db {}", databaseName, t);
            throw new EntException("Error creating tables to db " + databaseName, t);
        }
    }

    //---------------- DATA ------------------- START
    private void initMasterDefaultResource(SystemInstallationReport report, boolean checkOnStatup) throws EntException {
        logger.info(INIT_MSG_P, "Core", LOG_PREFIX);
        ComponentInstallationReport coreComponentReport = report.getComponentReport("entandoCore", false);
        if (coreComponentReport.getStatus().equals(SystemInstallationReport.Status.OK)) {
            String message = LOG_PREFIX + "( ok )  Already installed. " + coreComponentReport.getStatus() + "\n" + LOG_PREFIX;
            logger.debug(message);
            ApsSystemUtils.directStdoutTrace(message);
            return;
        }
        DataInstallationReport dataReport = coreComponentReport.getDataReport();
        try {
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "Starting installation\n" + LOG_PREFIX);
            String[] dataSourceNames = this.extractBeanNames(DataSource.class);
            for (String dataSourceName : dataSourceNames) {
                if ((report.getStatus().equals(SystemInstallationReport.Status.PORTING)
                        || report.getStatus().equals(SystemInstallationReport.Status.RESTORE)) && checkOnStatup) {
                    dataReport.getDatabaseStatus().put(dataSourceName, report.getStatus());
                    report.setUpdated();
                    String message = LOG_PREFIX + "( ok )  " + dataSourceName + " already installed. " + report.getStatus() + "\n" + LOG_PREFIX;
                    logger.debug(message);
                    ApsSystemUtils.directStdoutTrace(message);
                    continue;
                }
                SystemInstallationReport.Status schemaStatus = dataReport.getDatabaseStatus().get(dataSourceName);
                if (SystemInstallationReport.isSafeStatus(schemaStatus)) {
                    String message = LOG_PREFIX + "( ok )  " + dataSourceName + " already installed. " + report.getStatus() + "\n" + LOG_PREFIX;
                    ApsSystemUtils.directStdoutTrace(message);
                    continue;
                }
                Resource resource = (Environment.test.equals(this.getEnvironment()))
                        ? this.getTestSqlResources().get(dataSourceName)
                        : this.getEntandoDefaultSqlResources().get(dataSourceName);
                String script = this.readFile(resource);
                if (null != script && script.trim().length() != 0) {
                    if (checkOnStatup) {
                        dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.INCOMPLETE);
                        DataSource dataSource = (DataSource) this.getBeanFactory().getBean(dataSourceName);
                        this.getDatabaseRestorer().initOracleSchema(dataSource);
                        TableDataUtils.valueDatabase(script, dataSourceName, dataSource, null);
                        dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.OK);
                        ApsSystemUtils.directStdoutTrace("|   ( ok )  " + dataSourceName);
                    } else {
                        dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.SKIPPED);
                    }
                    report.setUpdated();
                } else {
                    ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( !! )  skipping " + dataSourceName + ": not available");
                    dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.NOT_AVAILABLE);
                    report.setUpdated();
                }
            }
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "\n" + LOG_PREFIX + "Installation complete\n" + LOG_PREFIX);
            logger.debug(LOG_PREFIX + "\n" + LOG_PREFIX + "Installation complete\n" + LOG_PREFIX);
        } catch (Throwable t) {
            logger.error("Error initializating master DefaultResource", t);
            throw new EntException("Error initializating master DefaultResource", t);
        }
    }

    public void initComponentDefaultResources(Component componentConfiguration,
            SystemInstallationReport report, boolean checkOnStatup) throws EntException {
        logger.info(INIT_MSG_P, componentConfiguration.getCode(), LOG_PREFIX);
        ComponentInstallationReport componentReport = report.getComponentReport(componentConfiguration.getCode(), false);
        if (componentReport.getStatus().equals(SystemInstallationReport.Status.OK)) {
            logger.debug(LOG_PREFIX + "( ok )  Already installed\n" + LOG_PREFIX);
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( ok )  Already installed\n" + LOG_PREFIX);
            return;
        } else if (componentReport.getStatus().equals(SystemInstallationReport.Status.UNINSTALLED)) {
            logger.debug(LOG_PREFIX + "( ok )  Uninstalled\n" + LOG_PREFIX);
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( ok )  Uninstalled\n" + LOG_PREFIX);
            return;
        }
        DataInstallationReport dataReport = componentReport.getDataReport();
        try {
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "Starting installation\n" + LOG_PREFIX);
            String[] dataSourceNames = this.extractBeanNames(DataSource.class);
            for (String dataSourceName : dataSourceNames) {
                if ((report.getStatus().equals(SystemInstallationReport.Status.PORTING)
                        || report.getStatus().equals(SystemInstallationReport.Status.RESTORE)) && checkOnStatup) {
                    dataReport.getDatabaseStatus().put(dataSourceName, report.getStatus());
                    ApsSystemUtils.directStdoutTrace("|   ( ok )  " + dataSourceName);
                    report.setUpdated();
                    continue;
                }
                DataSource dataSource = (DataSource) this.getBeanFactory().getBean(dataSourceName);
                SystemInstallationReport.Status dataStatus = dataReport.getDatabaseStatus().get(dataSourceName);
                if (SystemInstallationReport.isSafeStatus(dataStatus)) {
                    logger.debug(LOG_PREFIX + "( ok )  Already installed\n" + LOG_PREFIX);
                    ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( ok )  Already installed\n" + LOG_PREFIX);
                    continue;
                }
                Map<String, ComponentEnvironment> environments = componentConfiguration.getEnvironments();
                String compEnvKey = (Environment.test.equals(this.getEnvironment()))
                        ? Environment.test.toString() : Environment.production.toString();
                ComponentEnvironment componentEnvironment = (null != environments) ? environments.get(compEnvKey) : null;
                Resource resource = (null != componentEnvironment) ? componentEnvironment.getSqlResources(dataSourceName) : null;
                String script = (null != resource) ? this.readFile(resource) : null;
                if (null != script && script.trim().length() > 0) {
                    if (checkOnStatup) {
                        dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.INCOMPLETE);
                        this.getDatabaseRestorer().initOracleSchema(dataSource);
                        TableDataUtils.valueDatabase(script, dataSourceName, dataSource, dataReport);
                        ApsSystemUtils.directStdoutTrace("|   ( ok )  " + dataSourceName);
                        dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.OK);
                    } else {
                        dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.SKIPPED);
                    }
                    report.setUpdated();
                } else {
                    ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( !! )  skipping " + dataSourceName + ": not available");
                    dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.NOT_AVAILABLE);
                    report.setUpdated();
                }
            }
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "\n" + LOG_PREFIX + "Installation complete\n" + LOG_PREFIX);
            logger.debug(LOG_PREFIX + "\n" + LOG_PREFIX + "Installation complete\n" + LOG_PREFIX);
        } catch (Throwable t) {
            logger.error("Error restoring default resources of component {}", componentConfiguration.getCode(), t);
            throw new EntException("Error restoring default resources of component " + componentConfiguration.getCode(), t);
        }
    }

    public void uninstallComponentResources(Component componentConfiguration,
                                            SystemInstallationReport report) throws EntException {
        logger.info(INIT_MSG_P, componentConfiguration.getCode(), LOG_PREFIX);
        ComponentInstallationReport componentReport = report.getComponentReport(componentConfiguration.getCode(), false);
        if (componentReport.getStatus().equals(SystemInstallationReport.Status.UNINSTALLED)) {

            logger.debug(LOG_PREFIX + "( ok ) Already uninstalled\n" + LOG_PREFIX);
            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( ok ) Already uninstalled\n" + LOG_PREFIX);

        } else if (componentReport.getStatus().equals(SystemInstallationReport.Status.OK)) {

            DataInstallationReport dataReport = componentReport.getDataReport();
            try {
                ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "Starting uninstall\n" + LOG_PREFIX);
                ComponentUninstallerInfo uninstallInfo = componentConfiguration.getUninstallerInfo();

                // Remove sqlResources
                String[] dataSourceNames = this.extractBeanNames(DataSource.class);
                for (String dataSourceName : dataSourceNames) {

                    DataSource dataSource = (DataSource) this.getBeanFactory().getBean(dataSourceName);
                    SystemInstallationReport.Status dataStatus = dataReport.getDatabaseStatus().get(dataSourceName);
                    if (SystemInstallationReport.isSafeStatus(dataStatus)) {
                        // We can proceed with removing the stuff
                        Resource sqlResource = uninstallInfo.getSqlResources(dataSourceName);
                        String script = (null != sqlResource) ? this.readFile(sqlResource) : null;
                        if (null != script && script.trim().length() > 0) {
                            dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.INCOMPLETE);
                            TableDataUtils.valueDatabase(script, dataSourceName, dataSource, dataReport);
                            ApsSystemUtils.directStdoutTrace("|   ( ok )  " + dataSourceName);
                            dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.UNINSTALLED);
                            report.setUpdated();
                        } else {
                            ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "( !! )  skipping " + dataSourceName + ": not available");
                            dataReport.getDatabaseStatus().put(dataSourceName, SystemInstallationReport.Status.NOT_AVAILABLE);
                            report.setUpdated();
                        }
                    }
                }

                report.removeComponentReport(componentConfiguration.getCode());
                report.setUpdated();
                ApsSystemUtils.directStdoutTrace(LOG_PREFIX + "\n" + LOG_PREFIX + "Uninstall complete\n" + LOG_PREFIX);
                logger.debug(LOG_PREFIX + "\n" + LOG_PREFIX + "Uninstall complete\n" + LOG_PREFIX);
            } catch (Throwable t) {
                logger.error("Error removing component {}", componentConfiguration.getCode(), t);
                throw new EntException("Error removing component " + componentConfiguration.getCode(), t);
            }
        }
    }

    private void restoreDefaultDump() throws EntException {
        try {
            String[] dataSourceNames = this.extractBeanNames(DataSource.class);
            Map<String, Resource> defaultDump = this.getDefaultSqlDump();
            if (null == defaultDump || defaultDump.isEmpty()) {
                return;
            }
            for (String dataSourceName : dataSourceNames) {
                DataSource dataSource = (DataSource) this.getBeanFactory().getBean(dataSourceName);
                Resource resource = defaultDump.get(dataSourceName);
                String script = this.readFile(resource);
                if (null != script && script.trim().length() > 0) {
                    this.getDatabaseRestorer().initOracleSchema(dataSource);
                    TableDataUtils.valueDatabase(script, dataSourceName, dataSource, null);
                }
            }
        } catch (Throwable t) {
            logger.error("Error restoring default Dump", t);
            throw new EntException("Error restoring default Dump", t);
        }
    }

    private String readFile(Resource resource) throws Throwable {
        if (resource == null) {
            return null;
        }
        InputStream is = null;
        String text = null;
        try {
            is = resource.getInputStream();
            if (null == is) {
                return null;
            }
            text = FileTextReader.getText(is, "UTF-8");
        } catch (Throwable t) {
            logger.error("Error reading resource", t);
            throw new EntException("Error reading resource", t);
        } finally {
            if (null != is) {
                is.close();
            }
        }
        return text;
    }

    //---------------- DATA ------------------- END
    @Override
    public void createBackup() throws EntException {
        if (this.getStatus() != STATUS_READY) {
            return;
        }
        try {
            this.setStatus(DatabaseManager.STATUS_DUMPING_IN_PROGRESS);
            DatabaseDumperThread thread = new DatabaseDumperThread(this);
            String threadName = "DatabaseDumper_" + DateConverter.getFormattedDate(new Date(), "yyyyMMddHHmmss");
            thread.setName(threadName);
            thread.start();
        } catch (Throwable t) {
            this.setStatus(DatabaseManager.STATUS_READY);
            logger.error("Error while creating backup", t);
            throw new EntException("Error while creating backup", t);
        }
    }

    protected void executeBackup() throws EntException {
        try {

            this.getDatabaseDumper().createBackup(this.getEnvironment(), this.extractReport());
        } catch (Throwable t) {
            logger.error("Error while creating backup", t);
            throw new EntException("Error while creating backup", t);
        } finally {
            this.setStatus(DatabaseManager.STATUS_READY);
        }
    }

    @Override
    public void deleteBackup(String subFolderName) throws EntException {
        try {
            String baseDir = this.getLocalBackupsFolder();
            String directoryName = baseDir + subFolderName;
            this.getStorageManager().deleteDirectory(directoryName, true);
        } catch (Throwable t) {
            logger.error("Error while deleting backup", t);
            throw new EntException("Error while deleting backup", t);
        }
    }

    protected DataSourceDumpReport getLastDumpReport() throws EntException {
        if (Environment.develop.equals(this.getEnvironment())) {
            return this.getBackupReport(this.getEnvironment().toString());
        }
        List<DataSourceDumpReport> reports = this.getBackupReports();
        if (null == reports || reports.isEmpty()) {
            return null;
        }
        return reports.get(reports.size() - 1);
    }

    @Override
    public DataSourceDumpReport getBackupReport(String subFolderName) {
        try {
            if (this.checkBackupFolder(subFolderName)) {
                return this.getDumpReport(subFolderName);
            }
        } catch (Throwable t) {
            logger.error("Error while extracting Backup Report of subfolder {}", subFolderName, t);
            throw new RuntimeException("Error while extracting Backup Report of subfolder " + subFolderName);
        }
        return null;
    }

    @Override
    public List<DataSourceDumpReport> getBackupReports() {
        List<DataSourceDumpReport> reports = new ArrayList<DataSourceDumpReport>();
        try {
            String[] children = this.getStorageManager().listDirectory(this.getLocalBackupsFolder(), true); //backupsFolder.list();
            if (null == children || children.length == 0) {
                return null;
            }
            for (String subFolderName : children) {
                if (this.checkBackupFolder(subFolderName)) {
                    DataSourceDumpReport report = this.getDumpReport(subFolderName);
                    reports.add(report);
                }
            }
            Collections.sort(reports, new BeanComparator("date"));
        } catch (Throwable t) {
            logger.error("Error while extracting Backup Reports", t);
            throw new RuntimeException("Error while extracting Backup Reports");
        }
        return reports;
    }

    private boolean checkBackupFolder(String subFolderName) throws EntException, IOException {
        String localBackupFolderRoot = this.getLocalBackupsFolder();
        String reportFileName = localBackupFolderRoot + subFolderName + File.separator + DUMP_REPORT_FILE_NAME;

        if (StorageManagerUtil.doesPathContainsPath(localBackupFolderRoot, reportFileName)) {
            if (!this.getStorageManager().exists(reportFileName, true)) {
                logger.warn("dump report file name not found in path {}", reportFileName);
                return false;
            }
        } else {
            throw new EntRuntimeException(
                    String.format("Path validation failed: \"%s\" not in \"%s\"", reportFileName, localBackupFolderRoot)
            );
        }

        return true;
    }

    private DataSourceDumpReport getDumpReport(String subFolderName) throws EntException {
        InputStream is = null;
        DataSourceDumpReport report = null;
        try {
            String key = this.getLocalBackupsFolder() + subFolderName + File.separator + DUMP_REPORT_FILE_NAME;
            is = this.getStorageManager().getStream(key, true);
            String xml = FileTextReader.getText(is);
            report = new DataSourceDumpReport(xml);
        } catch (Throwable t) {
            logger.error("Error while extracting Dump Report of subfolder {}", subFolderName, t);
            throw new RuntimeException("Error while extracting Dump Report of subfolder " + subFolderName);
        } finally {
            if (null != is) {
                try {
                    is.close();
                } catch (IOException ex) {
                }
            }
        }
        return report;
    }

    @Override
    public boolean dropAndRestoreBackup(String subFolderName) throws EntException {
        try {
            if (!this.checkBackupFolder(subFolderName)) {
                logger.error("backup not available - subfolder '{}'", subFolderName);
                return false;
            }
            //TODO future improvement - execute 'lifeline' backup
            this.getDatabaseRestorer().dropAndRestoreBackup(subFolderName);
            ApsWebApplicationUtils.executeSystemRefresh(this.getServletContext());
            return true;
        } catch (Throwable t) {
            //TODO future improvement - restore 'lifeline' backup
            logger.error("Error while restoring backup - subfolder {}", subFolderName, t);
            throw new EntException("Error while restoring backup - subfolder " + subFolderName, t);
        } finally {
            //TODO future improvement - delete 'lifeline' backup
        }
    }

    private boolean restoreBackup(String subFolderName) throws EntException {
        try {
            if (!this.checkBackupFolder(subFolderName)) {
                logger.error("backup not available - subfolder '{}'", subFolderName);
                return false;
            }
            this.getDatabaseRestorer().restoreBackup(subFolderName);
            return true;
        } catch (Throwable t) {
            logger.error("Error while restoring local backup", t);
            throw new EntException("Error while restoring local backup", t);
        }
    }

    private String[] extractBeanNames(Class beanClass) {
        ListableBeanFactory factory = (ListableBeanFactory) this.getBeanFactory();
        return factory.getBeanNamesForType(beanClass);
    }

    @Override
    public InputStream getTableDump(String tableName, String dataSourceName, String subFolderName) throws EntException {
        try {
            if (null == subFolderName) {
                return null;
            }
            StringBuilder fileName = new StringBuilder(this.getLocalBackupsFolder());
            fileName.append(subFolderName).append(File.separator)
                    .append(dataSourceName).append(File.separator).append(tableName).append(".sql");
            return this.getStorageManager().getStream(fileName.toString(), true);
        } catch (Throwable t) {
            logger.error("Error while extracting table dump - " + "table '{}' - datasource '{}' - SubFolder '{}'", tableName, dataSourceName, subFolderName, t);
            throw new RuntimeException("Error while extracting table dump - " + "table '" + tableName + "' - datasource '" + dataSourceName + "' - SubFolder '" + subFolderName + "'", t);
        }
    }

    @Override
    public DatabaseType getDatabaseType(DataSource dataSource) throws EntException {
        return this.getDatabaseRestorer().getType(dataSource);
    }

    private IStorageManager getStorageManager() {
        return (IStorageManager) this.getBeanFactory().getBean("StorageManager");
    }

    protected String getLocalBackupsFolder() {
        return this.getDatabaseDumper().getLocalBackupsFolder();
    }

    @Override
    public Map<String, List<String>> getEntandoTableMapping() {
        return entandoTableMapping;
    }

    public void setEntandoTableMapping(Map<String, List<String>> entandoTableMapping) {
        this.entandoTableMapping = entandoTableMapping;
    }

    protected Map<String, Resource> getEntandoDefaultSqlResources() {
        return entandoDefaultSqlResources;
    }

    public void setEntandoDefaultSqlResources(Map<String, Resource> entandoDefaultSqlResources) {
        this.entandoDefaultSqlResources = entandoDefaultSqlResources;
    }

    protected Map<String, Resource> getTestSqlResources() {
        return testSqlResources;
    }

    public void setTestSqlResources(Map<String, Resource> testSqlResources) {
        this.testSqlResources = testSqlResources;
    }

    protected Map<String, Resource> getDefaultSqlDump() {
        return defaultSqlDump;
    }

    public void setDefaultSqlDump(Map<String, Resource> defaultSqlDump) {
        this.defaultSqlDump = defaultSqlDump;
    }

    @Override
    public int getStatus() {
        return status;
    }

    protected void setStatus(int status) {
        this.status = status;
    }

    protected DatabaseDumper getDatabaseDumper() {
        return databaseDumper;
    }

    public void setDatabaseDumper(DatabaseDumper databaseDumper) {
        this.databaseDumper = databaseDumper;
    }

    protected DatabaseRestorer getDatabaseRestorer() {
        return databaseRestorer;
    }

    public void setDatabaseRestorer(DatabaseRestorer databaseRestorer) {
        this.databaseRestorer = databaseRestorer;
    }

    protected ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public void setServletContext(ServletContext servletContext) {
        this.servletContext = servletContext;
    }
}
