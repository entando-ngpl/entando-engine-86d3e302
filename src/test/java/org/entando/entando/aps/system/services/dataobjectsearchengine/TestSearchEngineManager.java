/*
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
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
package org.entando.entando.aps.system.services.dataobjectsearchengine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.agiletec.aps.BaseTestCase;
import com.agiletec.aps.system.SystemConstants;
import com.agiletec.aps.system.common.entity.model.attribute.TextAttribute;
import com.agiletec.aps.system.common.searchengine.IndexableAttributeInterface;
import com.agiletec.aps.system.common.tree.ITreeNode;
import com.agiletec.aps.system.services.category.Category;
import com.agiletec.aps.system.services.category.ICategoryManager;
import com.agiletec.aps.system.services.group.Group;
import org.entando.entando.aps.system.services.dataobject.model.DataObject;
import org.entando.entando.aps.system.services.searchengine.FacetedContentsResult;
import org.entando.entando.aps.system.services.searchengine.SearchEngineFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Test del servizio detentore delle operazioni sul motore di ricerca.
 *
 * @author E.Santoboni
 */
class TestSearchEngineManager extends BaseTestCase {

	@Test
    void testSearchAllContents() throws Throwable {
		try {
			Thread thread = this.dataObjectSearchEngineManager.startReloadDataObjectsReferences();
			thread.join();
			Set<String> allowedGroup = new HashSet<String>();
			SearchEngineFilter[] filters = {};
			SearchEngineManager sem = (SearchEngineManager) this.dataObjectSearchEngineManager;
			List<String> freeContentsId = sem.searchEntityId(filters, null, allowedGroup);
			assertNotNull(freeContentsId);
			allowedGroup.add(Group.ADMINS_GROUP_NAME);
			List<String> allContentsId = sem.searchEntityId(filters, null, allowedGroup);
			assertNotNull(allContentsId);
			assertTrue(allContentsId.size() > freeContentsId.size());
		} catch (Throwable t) {
			throw t;
		}
	}

	@Test
    void testSearchContentsId_1() throws Throwable {
		try {
			DataObject content_1 = this.createDataObject_1();
			this.dataObjectSearchEngineManager.deleteIndexedEntity(content_1.getId());
			this.dataObjectSearchEngineManager.addEntityToIndex(content_1);

			DataObject content_2 = this.createDataObject_2();
			this.dataObjectSearchEngineManager.deleteIndexedEntity(content_2.getId());
			this.dataObjectSearchEngineManager.addEntityToIndex(content_2);

			List<String> contentsId = this.dataObjectSearchEngineManager.searchEntityId("it", "San meravigliosa", null);
			assertNotNull(contentsId);
			assertTrue(contentsId.contains(content_1.getId()));
			contentsId = this.dataObjectSearchEngineManager.searchEntityId("en", "Petersburg wonderful", null);
			assertNotNull(contentsId);
			assertTrue(contentsId.contains(content_1.getId()));
			contentsId = this.dataObjectSearchEngineManager.searchEntityId("en", "meravigliosa", null);
			assertNotNull(contentsId);
			assertFalse(contentsId.contains(content_1.getId()));
		} catch (Throwable t) {
			throw t;
		}
	}

	@Test
    void testSearchContentsId_2() throws Throwable {
		try {
			Thread thread = this.dataObjectSearchEngineManager.startReloadDataObjectsReferences();
			thread.join();

			Set<String> allowedGroup = new HashSet<String>();
			List<String> contentsId = this.dataObjectSearchEngineManager.searchEntityId("it", "Corpo coach", allowedGroup);
			assertNotNull(contentsId);
			assertFalse(contentsId.contains("ART104"));

			allowedGroup.add("coach");
			contentsId = this.dataObjectSearchEngineManager.searchEntityId("it", "testo coach", allowedGroup);
			assertNotNull(contentsId);
			assertTrue(contentsId.contains("ART104"));//coach content

			contentsId = this.dataObjectSearchEngineManager.searchEntityId("it", "Titolo Evento 4", allowedGroup);
			assertNotNull(contentsId);
			assertTrue(contentsId.contains("EVN194"));//free content

			Set<String> allowedGroup2 = new HashSet<String>();
			allowedGroup2.add(Group.ADMINS_GROUP_NAME);
			contentsId = this.dataObjectSearchEngineManager.searchEntityId("it", "testo coach", allowedGroup2);
			assertNotNull(contentsId);
			assertTrue(contentsId.contains("ART104"));//coach content

		} catch (Throwable t) {
			throw t;
		}
	}

	@Test
    void testSearchContentsId_3() throws Throwable {
		try {
			DataObject content_1 = this.createDataObject_1();
			content_1.setMainGroup(Group.ADMINS_GROUP_NAME);
			this.dataObjectSearchEngineManager.deleteIndexedEntity(content_1.getId());
			this.dataObjectSearchEngineManager.addEntityToIndex(content_1);

			DataObject content_2 = this.createDataObject_2();
			this.dataObjectSearchEngineManager.deleteIndexedEntity(content_2.getId());
			this.dataObjectSearchEngineManager.addEntityToIndex(content_2);

			List<String> allowedGroup = new ArrayList<String>();
			allowedGroup.add(Group.FREE_GROUP_NAME);
			List<String> contentsId = this.dataObjectSearchEngineManager.searchEntityId("it", "San meravigliosa", allowedGroup);
			assertNotNull(contentsId);
			assertFalse(contentsId.contains(content_1.getId()));
			allowedGroup.add("secondaryGroup");
			contentsId = this.dataObjectSearchEngineManager.searchEntityId("it", "San meravigliosa", allowedGroup);
			assertNotNull(contentsId);
			assertTrue(contentsId.contains(content_1.getId()));
		} catch (Throwable t) {
			throw t;
		}
	}

	@Test
    void testSearchContentsId_4() throws Throwable {
		try {
			Thread thread = this.dataObjectSearchEngineManager.startReloadDataObjectsReferences();
			thread.join();
			SearchEngineManager sem = (SearchEngineManager) this.dataObjectSearchEngineManager;
			SearchEngineFilter filterByType = new SearchEngineFilter(IIndexerDAO.DATAOBJECT_TYPE_FIELD_NAME, "ART");
			SearchEngineFilter[] filters = {filterByType};
			List<String> allowedGroup = new ArrayList<String>();
			allowedGroup.add(Group.FREE_GROUP_NAME);
			List<String> contentsId = sem.searchEntityId(filters, null, allowedGroup);
			assertNotNull(contentsId);
			String[] expected1 = {"ART180", "ART1", "ART187", "ART121"};
			this.verify(contentsId, expected1);
			Category cat1 = this._categoryManager.getCategory("cat1");
			List<ITreeNode> categories = new ArrayList<ITreeNode>();
			categories.add(cat1);
			contentsId = sem.searchEntityId(filters, categories, allowedGroup);
			assertNotNull(contentsId);
			String[] expected2 = {"ART180"};
			this.verify(contentsId, expected2);
		} catch (Throwable t) {
			throw t;
		}
	}

	@Test
    void testSearchContentsId_5() throws Throwable {
		try {
			Thread thread = this.dataObjectSearchEngineManager.startReloadDataObjectsReferences();
			thread.join();
			SearchEngineManager sem = (SearchEngineManager) this.dataObjectSearchEngineManager;
			Category general_cat2 = this._categoryManager.getCategory("general_cat2");
			List<ITreeNode> categories = new ArrayList<ITreeNode>();
			categories.add(general_cat2);
			List<String> allowedGroup = new ArrayList<String>();
			allowedGroup.add(Group.FREE_GROUP_NAME);
			List<String> contentsId = sem.searchEntityId(null, categories, allowedGroup);
			assertNotNull(contentsId);
			assertTrue(contentsId.isEmpty());
			allowedGroup.add(Group.ADMINS_GROUP_NAME);
			contentsId = sem.searchEntityId(null, categories, allowedGroup);
			String[] expected1 = {"ART111", "ART120"};
			this.verify(contentsId, expected1);
			Category general_cat1 = this._categoryManager.getCategory("general_cat1");
			categories.add(general_cat1);
			contentsId = sem.searchEntityId(null, categories, allowedGroup);
			assertNotNull(contentsId);
			String[] expected2 = {"ART111"};
			this.verify(contentsId, expected2);
		} catch (Throwable t) {
			throw t;
		}
	}

    @Disabled("temporary disabling")
	@Test
    void testSearchContentsId_6() throws Throwable {
		try {
			Thread thread = this.dataObjectSearchEngineManager.startReloadDataObjectsReferences();
			thread.join();
			SearchEngineManager sem = (SearchEngineManager) this.dataObjectSearchEngineManager;
			Category general = this._categoryManager.getCategory("general");
			List<ITreeNode> categories = new ArrayList<ITreeNode>();
			categories.add(general);
			List<String> allowedGroup = new ArrayList<String>();
			allowedGroup.add(Group.ADMINS_GROUP_NAME);
			List<String> contentsId = sem.searchEntityId(null, categories, allowedGroup);
			assertNotNull(contentsId);
			String[] expected1 = {"ART122", "ART102", "ART111", "ART120"};
			this.verify(contentsId, expected1);
		} catch (Throwable t) {
			throw t;
		}
	}

	@Test
    void testSearchContentsId_7() throws Throwable {
		try {
			DataObject content_1 = this.createDataObject_1();
			this.dataObjectSearchEngineManager.deleteIndexedEntity(content_1.getId());
			this.dataObjectSearchEngineManager.addEntityToIndex(content_1);

			DataObject content_2 = this.createDataObject_2();
			this.dataObjectSearchEngineManager.deleteIndexedEntity(content_2.getId());
			this.dataObjectSearchEngineManager.addEntityToIndex(content_2);

			DataObject content_3 = this.createDataObject_3();
			this.dataObjectSearchEngineManager.deleteIndexedEntity(content_3.getId());
			this.dataObjectSearchEngineManager.addEntityToIndex(content_3);

			//San Pietroburgo ?? una citt?? meravigliosa W3C-WAI
			//100
			//Il turismo ha incrementato pi?? del 20 per cento nel 2011-2013, quando la Croazia ha aderito all'Unione europea. Consegienda di questo aumento ?? una serie di modernizzazione di alloggi di recente costruzione, tra cui circa tre dozzine di ostelli.
			//101
			//La vita ?? una cosa meravigliosa
			//103
			SearchEngineManager sem = (SearchEngineManager) this.dataObjectSearchEngineManager;

			List<String> allowedGroup = new ArrayList<String>();
			allowedGroup.add(Group.FREE_GROUP_NAME);
			SearchEngineFilter filter1 = new SearchEngineFilter("it", "San meravigliosa", SearchEngineFilter.TextSearchOption.ALL_WORDS);
			SearchEngineFilter[] filters1 = {filter1};
			List<String> contentsId = sem.searchEntityId(filters1, null, allowedGroup);
			assertNotNull(contentsId);
			assertEquals(1, contentsId.size());
			assertTrue(contentsId.contains(content_1.getId()));

			SearchEngineFilter filter2 = new SearchEngineFilter("it", "San meravigliosa", SearchEngineFilter.TextSearchOption.AT_LEAST_ONE_WORD);
			SearchEngineFilter[] filters2 = {filter2};
			contentsId = sem.searchEntityId(filters2, null, allowedGroup);
			assertNotNull(contentsId);
			assertEquals(2, contentsId.size());
			assertTrue(contentsId.contains(content_1.getId()));
			assertTrue(contentsId.contains(content_3.getId()));

			SearchEngineFilter filter3 = new SearchEngineFilter("it", "San meravigliosa", SearchEngineFilter.TextSearchOption.EXACT);
			SearchEngineFilter[] filters3 = {filter3};
			contentsId = sem.searchEntityId(filters3, null, allowedGroup);
			assertNotNull(contentsId);
			assertEquals(0, contentsId.size());

			SearchEngineFilter filter4 = new SearchEngineFilter("it", "una cosa meravigliosa", SearchEngineFilter.TextSearchOption.EXACT);
			SearchEngineFilter[] filters4 = {filter4};
			contentsId = sem.searchEntityId(filters4, null, allowedGroup);
			assertNotNull(contentsId);
			assertEquals(1, contentsId.size());
			assertTrue(contentsId.contains(content_3.getId()));
		} catch (Throwable t) {
			throw t;
		}
	}

	@Test
    void testFacetedAllContents() throws Throwable {
		try {
			Thread thread = this.dataObjectSearchEngineManager.startReloadDataObjectsReferences();
			thread.join();
			SearchEngineManager sem = (SearchEngineManager) this.dataObjectSearchEngineManager;
			Set<String> allowedGroup = new HashSet<String>();
			allowedGroup.add(Group.ADMINS_GROUP_NAME);
			SearchEngineFilter[] filters = {};
			FacetedContentsResult result = sem.searchFacetedEntities(filters, null, allowedGroup);
			assertNotNull(result);
			assertNotNull(result.getContentsId());
			assertNotNull(result.getOccurrences());
			assertTrue(result.getContentsId().size() > 0);
			assertTrue(result.getOccurrences().size() > 0);
		} catch (Throwable t) {
			throw t;
		}
	}

	@Test
    void testSearchFacetedContents_1() throws Throwable {
		try {
			Thread thread = this.dataObjectSearchEngineManager.startReloadDataObjectsReferences();
			thread.join();
			SearchEngineManager sem = (SearchEngineManager) this.dataObjectSearchEngineManager;
			Category general = this._categoryManager.getCategory("general");
			List<ITreeNode> categories = new ArrayList<ITreeNode>();
			categories.add(general);
			List<String> allowedGroup = new ArrayList<String>();
			allowedGroup.add(Group.FREE_GROUP_NAME);
			allowedGroup.add(Group.ADMINS_GROUP_NAME);
			FacetedContentsResult result = sem.searchFacetedEntities(null, categories, allowedGroup);
			assertNotNull(result);
			String[] expected1 = {"ART122", "ART102", "ART111", "ART120"};
			this.verify(result.getContentsId(), expected1);
			assertEquals(4, result.getOccurrences().size());
		} catch (Throwable t) {
			throw t;
		}
	}

	private void verify(List<String> contentsId, String[] array) {
		assertEquals(array.length, contentsId.size());
		for (int i = 0; i < array.length; i++) {
			assertTrue(contentsId.contains(array[i]));
		}
	}

	private DataObject createDataObject_1() {
		DataObject content = new DataObject();
		content.setId("100");
		content.setMainGroup(Group.FREE_GROUP_NAME);
		content.addGroup("secondaryGroup");
		content.setTypeCode("ART");
		TextAttribute text = new TextAttribute();
		text.setName("Articolo");
		text.setType("Text");
		text.setIndexingType(IndexableAttributeInterface.INDEXING_TYPE_TEXT);
		text.setText("San Pietroburgo ?? una citt?? meravigliosa W3C-WAI", "it");
		text.setText("St. Petersburg is a wonderful city", "en");
		content.addAttribute(text);
		Category category1 = this._categoryManager.getCategory("resCat2");
		Category category2 = this._categoryManager.getCategory("general_cat3");
		content.addCategory(category1);
		content.addCategory(category2);
		return content;
	}

	private DataObject createDataObject_2() {
		DataObject content = new DataObject();
		content.setId("101");
		content.setMainGroup(Group.FREE_GROUP_NAME);
		content.addGroup("thirdGroup");
		content.setTypeCode("ART");
		TextAttribute text = new TextAttribute();
		text.setName("Articolo");
		text.setType("Text");
		text.setIndexingType(IndexableAttributeInterface.INDEXING_TYPE_TEXT);
		text.setText("Il turismo ha incrementato pi?? del 20 per cento nel 2011-2013, quando la Croazia ha aderito all'Unione europea. Consegienda di questo aumento ?? una serie di modernizzazione di alloggi di recente costruzione, tra cui circa tre dozzine di ostelli.", "it");
		text.setText("Tourism had shot up more than 20 percent from 2011 to 2013, when Croatia joined the European Union. Accompanying that rise is a raft of modernized and recently built lodgings, including some three dozen hostels.", "en");
		content.addAttribute(text);
		Category category1 = this._categoryManager.getCategory("resCat1");
		Category category2 = this._categoryManager.getCategory("general_cat2");
		content.addCategory(category1);
		content.addCategory(category2);
		return content;
	}

	private DataObject createDataObject_3() {
		DataObject content = new DataObject();
		content.setId("103");
		content.setMainGroup(Group.FREE_GROUP_NAME);
		content.setTypeCode("ART");
		TextAttribute text = new TextAttribute();
		text.setName("Articolo");
		text.setType("Text");
		text.setIndexingType(IndexableAttributeInterface.INDEXING_TYPE_TEXT);
		text.setText("La vita ?? una cosa meravigliosa", "it");
		text.setText("Life is a wonderful thing", "en");
		content.addAttribute(text);
		Category category = this._categoryManager.getCategory("general_cat1");
		content.addCategory(category);
		return content;
	}

    @BeforeEach
	private void init() throws Exception {
		try {
			this.dataObjectSearchEngineManager = (IDataObjectSearchEngineManager) this.getService("DataObjectSearchEngineManager");
			this._categoryManager = (ICategoryManager) this.getService(SystemConstants.CATEGORY_MANAGER);
		} catch (Exception e) {
			throw e;
		}
	}

	private IDataObjectSearchEngineManager dataObjectSearchEngineManager = null;
	private ICategoryManager _categoryManager;

}
