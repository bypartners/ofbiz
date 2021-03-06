/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.entity.test;

import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilXml;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericEntity;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericPK;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityConditionList;
import org.ofbiz.entity.condition.EntityExpr;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.config.DatasourceInfo;
import org.ofbiz.entity.config.EntityConfigUtil;
import org.ofbiz.entity.testtools.EntityTestCase;
import org.ofbiz.entity.transaction.GenericTransactionException;
import org.ofbiz.entity.transaction.TransactionUtil;
import org.ofbiz.entity.util.EntityFindOptions;
import org.ofbiz.entity.util.EntityListIterator;
import org.ofbiz.entity.util.EntitySaxReader;

public class EntityTestSuite extends EntityTestCase {

    public static final String module = EntityTestSuite.class.getName();
    /*
     * This sets how many values to insert when trying to create a large number of values.  10,000 causes HSQL to crash but is ok
     * with Derby.  Going up to 100,000 causes problems all around because Java List seems to be capped at about 65,000 values.
     *
     * NOTE: setting this lower so that the general tests don't take so long to run; to really push it can increase this number.
     */
    public static final long TEST_COUNT = 1000;

    public EntityTestSuite(String name) {
        super(name);
    }

    final static private int _level1max = 3;   // number of TestingNode entities to create

    /*
     * Tests storing values with the delegator's .create, .makeValue, and .storeAll methods
     */
    public void testMakeValue() throws Exception {
        // This method call directly stores a new value into the entity engine
        delegator.create("TestingType", "testingTypeId", "TEST-1", "description", "Testing Type #1");

        // This sequence creates the GenericValue entities first, puts them in a List, then calls the delegator to store them all
        List<GenericValue> newValues = new LinkedList<GenericValue>();

        newValues.add(delegator.makeValue("TestingType", "testingTypeId", "TEST-2", "description", "Testing Type #2"));
        newValues.add(delegator.makeValue("TestingType", "testingTypeId", "TEST-3", "description", "Testing Type #3"));
        newValues.add(delegator.makeValue("TestingType", "testingTypeId", "TEST-4", "description", "Testing Type #4"));
        delegator.storeAll(newValues);

        // finds a List of newly created values.  the second parameter specifies the fields to order results by.
        List<GenericValue> newlyCreatedValues = delegator.findList("TestingType", null, null, UtilMisc.toList("testingTypeId"), null, false);
        assertEquals("4 TestingTypes found", 4, newlyCreatedValues.size());
    }

    /*
     * Tests updating entities by doing a GenericValue .put(key, value) and .store()
     */
    public void testUpdateValue() throws Exception {
        // retrieve a sample GenericValue, make sure it's correct
        GenericValue testValue = delegator.findOne("TestingType", false, "testingTypeId", "TEST-1");
        assertEquals("Retrieved value has the correct description", "Testing Type #1", testValue.getString("description"));

        // now update and store it
        testValue.put("description", "New Testing Type #1");
        testValue.store();

        // now retrieve it again and make sure that the updated value is correct
        testValue = delegator.findOne("TestingType", false, "testingTypeId", "TEST-1");
        assertEquals("Retrieved value has the correct description", "New Testing Type #1", testValue.getString("description"));
    }

    /*
     * Tests XML serialization by serializing/deserializing a GenericValue
     */
    public void testXmlSerialization() throws Exception {
        // Must use the default delegator because the deserialized GenericValue can't
        // find the randomized one.
        Delegator localDelegator = DelegatorFactory.getDelegator("default");
        boolean transBegin = TransactionUtil.begin();
        localDelegator.create("TestingType", "testingTypeId", "TEST-5", "description", "Testing Type #5");
        GenericValue testValue = localDelegator.findOne("TestingType", false, "testingTypeId", "TEST-5");
        assertEquals("Retrieved value has the correct description", "Testing Type #5", testValue.getString("description"));
        String newValueStr = UtilXml.toXml(testValue);
        GenericValue newValue = (GenericValue) UtilXml.fromXml(newValueStr);
        assertEquals("Retrieved value has the correct description", "Testing Type #5", newValue.getString("description"));
        newValue.put("description", "XML Testing Type #5");
        newValue.store();
        newValue = localDelegator.findOne("TestingType", false, "testingTypeId", "TEST-5");
        assertEquals("Retrieved value has the correct description", "XML Testing Type #5", newValue.getString("description"));
        TransactionUtil.rollback(transBegin, null, null);
    }

    /*
     * Tests storing data with the delegator's .create method.  Also tests .findCountByCondition and .getNextSeqId
     */
    public void testCreateTree() throws Exception {
        // get how many child nodes did we have before creating the tree
        EntityCondition isChild = EntityCondition.makeCondition("primaryParentNodeId", EntityOperator.NOT_EQUAL, GenericEntity.NULL_FIELD);
        long alreadyStored = delegator.findCountByCondition("TestingNode", isChild, null, null);

        //
        // The tree has a root, the root has level1max children.
        //

        // create the root
        GenericValue root = delegator.create("TestingNode",
                        "testingNodeId", delegator.getNextSeqId("TestingNode"),
                        "primaryParentNodeId", GenericEntity.NULL_FIELD,
                        "description", "root");
        int level1;
        for(level1 = 0; level1 < _level1max; level1++) {
            String nextSeqId = delegator.getNextSeqId("TestingNode");
            GenericValue v = delegator.create("TestingNode", "testingNodeId", nextSeqId,
                                    "primaryParentNodeId", root.get("testingNodeId"),
                                    "description", "node-level #1");
            assertNotNull(v);
        }

        long created = level1;
        long newlyStored = delegator.findCountByCondition("TestingNode", isChild, null, null);

        // Normally, newlyStored = alreadyStored + created
        assertEquals("Created/Stored Nodes", created + alreadyStored, newlyStored);
    }

    /*
     * More tests of storing data with .storeAll.  Also prepares data for testing view-entities (see below.)
     */
    public void testAddMembersToTree() throws Exception {
        // get the level1 nodes
        EntityCondition isLevel1 = EntityCondition.makeCondition("primaryParentNodeId", EntityOperator.NOT_EQUAL, GenericEntity.NULL_FIELD);
        List<GenericValue> nodeLevel1 = delegator.findList("TestingNode", isLevel1, null, null, null, false);

        List<GenericValue> newValues = new LinkedList<GenericValue>();
        Timestamp now = UtilDateTime.nowTimestamp();

        for (GenericValue node: nodeLevel1) {
            GenericValue testing = delegator.makeValue("Testing",
                            "testingId", delegator.getNextSeqId("Testing"),
                            "testingTypeId", "TEST-1"
                   );
            testing.put("testingName", "leaf-#" + node.getString("testingNodeId"));
            testing.put("description", "level1 leaf");
            testing.put("comments", "No-comments");
            testing.put("testingSize", Long.valueOf(10));
            testing.put("testingDate", now);

            newValues.add(testing);
            GenericValue member = delegator.makeValue("TestingNodeMember",
                            "testingNodeId", node.get("testingNodeId"),
                            "testingId", testing.get("testingId")
                   );

            member.put("fromDate", now);
            member.put("thruDate", UtilDateTime.getNextDayStart(now));

            newValues.add(member);
        }
        int n = delegator.storeAll(newValues);
        assertEquals("Created/Stored Nodes", newValues.size(), n);
    }

    /*
     * Tests findByCondition and tests searching on a view-entity
     */
    public void testCountViews() throws Exception {
        EntityCondition isNodeWithMember = EntityCondition.makeCondition("testingId", EntityOperator.NOT_EQUAL, GenericEntity.NULL_FIELD);
        List<GenericValue> nodeWithMembers = delegator.findList("TestingNodeAndMember", isNodeWithMember, null, null, null, false);

        for (GenericValue v: nodeWithMembers) {
            Map<String, Object> fields = v.getAllFields();
            Debug.logInfo("--------------------------", module);
            //      For values of a map
            for (Map.Entry<String, Object> entry: fields.entrySet()) {
                String field = entry.getKey();
                Object value = entry.getValue();
                Debug.logInfo(field.toString() + " = " + ((value == null) ? "[null]" : value), module);
            }
        }
        long testingcount = delegator.findCountByCondition("Testing", null, null, null);
        assertEquals("Number of views should equal number of created entities in the test.", testingcount, nodeWithMembers.size());
    }

    /*
     * Tests findByCondition and a find by distinct
     */
    public void testFindDistinct() throws Exception {
        List<EntityExpr> exprList = UtilMisc.toList(
                EntityCondition.makeCondition("testingSize", EntityOperator.EQUALS, Long.valueOf(10)),
                EntityCondition.makeCondition("comments", EntityOperator.EQUALS, "No-comments"));
        EntityConditionList<EntityExpr> condition = EntityCondition.makeCondition(exprList);

        EntityFindOptions findOptions = new EntityFindOptions();
        findOptions.setDistinct(true);

        List<GenericValue> testingSize10 = delegator.findList("Testing", condition, UtilMisc.toSet("testingSize", "comments"), null, findOptions, false);
        Debug.logInfo("testingSize10 is " + testingSize10.size(), module);

        assertEquals("There should only be 1 result found by findDistinct()", 1, testingSize10.size());
    }

    /*
     * Tests a findByCondition using not like
     */
    public void testNotLike() throws Exception {
        EntityCondition cond  = EntityCondition.makeCondition("description", EntityOperator.NOT_LIKE, "root%");
        List<GenericValue> nodes = delegator.findList("TestingNode", cond, null, null, null, false);
        assertNotNull("Found nodes", nodes);

        for (GenericValue product: nodes) {
            String nodeId = product.getString("description");
            Debug.logInfo("Testing name - " + nodeId, module);
            assertFalse("No nodes starting w/ root", nodeId.startsWith("root"));
        }
    }

    /*
     * Tests foreign key integrity by trying to remove an entity which has foreign-key dependencies.  Should cause an exception.
     */
    public void testForeignKeyCreate() {
        try {
            String helperName = delegator.getEntityHelper("Testing").getHelperName();
            DatasourceInfo datasourceInfo = EntityConfigUtil.getDatasourceInfo(helperName);
            if (!datasourceInfo.useFks) {
                Debug.logInfo("Datasource " + datasourceInfo.name + " use-foreign-keys set to false, skipping testForeignKeyCreate", module);
                return;
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, module);
        }
        GenericEntityException caught = null;
        try {
            delegator.create("Testing", "testingId", delegator.getNextSeqId("Testing"), "testingTypeId", "NO-SUCH-KEY");
        } catch (GenericEntityException e) {
            caught = e;
        }
        assertNotNull("Foreign key referential integrity is not observed for create (INSERT)", caught);
        Debug.logInfo(caught.toString(), module);
    }

    /*
     * Tests foreign key integrity by trying to remove an entity which has foreign-key dependencies.  Should cause an exception.
     */
    public void testForeignKeyRemove() {
        try {
            String helperName = delegator.getEntityHelper("TestingNode").getHelperName();
            DatasourceInfo datasourceInfo = EntityConfigUtil.getDatasourceInfo(helperName);
            if (!datasourceInfo.useFks) {
                Debug.logInfo("Datasource " + datasourceInfo.name + " use-foreign-keys set to false, skipping testForeignKeyRemove", module);
                return;
            }
        } catch (GenericEntityException e) {
            Debug.logError(e, module);
        }
        GenericEntityException caught = null;
        try {
            EntityCondition isLevel1 = EntityCondition.makeCondition("description", EntityOperator.EQUALS, "node-level #1");
            delegator.removeByCondition("TestingNode", isLevel1);
        } catch (GenericEntityException e) {
            caught = e;
        }
        assertNotNull("Foreign key referential integrity is not observed for remove (DELETE)", caught);
        Debug.logInfo(caught.toString(), module);
    }

    /*
     * Tests the .getRelatedOne method and removeAll for removing entities
     */
    public void testRemoveNodeMemberAndTesting() throws Exception {
        //
        // Find the testing entities tru the node member and build a list of them
        //
        List<GenericValue> values = delegator.findList("TestingNodeMember", null, null, null, null, false);

        ArrayList<GenericValue> testings = new ArrayList<GenericValue>();

        for (GenericValue nodeMember: values) {
            testings.add(nodeMember.getRelatedOne("Testing", false));
        }
        // and remove the nodeMember afterwards
        delegator.removeAll(values);
        values = delegator.findList("TestingNodeMember", null, null, null, null, false);
        assertEquals("No more Node Member entities", 0, values.size());

        delegator.removeAll(testings);
        values = delegator.findList("Testing", null, null, null, null, false);
        assertEquals("No more Testing entities", 0, values.size());
    }

    /*
     * Tests the storeByCondition operation
     */
    public void testStoreByCondition() throws Exception {
        // change the description of all the level1 nodes
        EntityCondition isLevel1 = EntityCondition.makeCondition("description", EntityOperator.EQUALS, "node-level #1");
        Map<String, String> fieldsToSet = UtilMisc.toMap("description", "node-level #1 (updated)");
        delegator.storeByCondition("TestingNode", fieldsToSet, isLevel1);
        List<GenericValue> updatedNodes = delegator.findByAnd("TestingNode", fieldsToSet, null, false);
        int n = updatedNodes.size();
        assertTrue("testStoreByCondition updated nodes > 0", n > 0);
    }

    /*
     * Tests the .removeByCondition method for removing entities directly
     */
    public void testRemoveByCondition() throws Exception {
        //
        // remove all the level1 nodes by using a condition on the description field
        //
        EntityCondition isLevel1 = EntityCondition.makeCondition("description", EntityOperator.EQUALS, "node-level #1 (updated)");
        int n = delegator.removeByCondition("TestingNode", isLevel1);
        assertTrue("testRemoveByCondition nodes > 0", n > 0);
    }

    /*
     * Test the .removeByPrimaryKey by using findByCondition and then retrieving the GenericPk from a GenericValue
     */
    public void testRemoveByPK() throws Exception {
        //
        // Find all the root nodes,
        // delete them their primary key
        //
        EntityCondition isRoot = EntityCondition.makeCondition("primaryParentNodeId", EntityOperator.EQUALS, GenericEntity.NULL_FIELD);
        List<GenericValue> rootValues = delegator.findList("TestingNode", isRoot, UtilMisc.toSet("testingNodeId"), null, null, false);

        for (GenericValue value: rootValues) {
            GenericPK pk = value.getPrimaryKey();
            int del = delegator.removeByPrimaryKey(pk);
            assertEquals("Removing Root by primary key", 1, del);
        }

        // no more TestingNode should be in the data base anymore.

        List<GenericValue> testingNodes = delegator.findList("TestingNode", null, null, null, null, false);
        assertEquals("No more TestingNode after removing the roots", 0, testingNodes.size());
    }

    /*
     * Tests the .removeAll method only.
     */
    public void testRemoveType() throws Exception {
        List<GenericValue> values = delegator.findList("TestingType", null, null, null, null, false);
        delegator.removeAll(values);

        // now make sure there are no more of these
        values = delegator.findList("TestingType", null, null, null, null, false);
        assertEquals("No more TestingTypes after remove all", 0, values.size());
    }

    /*
     * This test will create a large number of unique items and add them to the delegator at once
     */
    public void testCreateManyAndStoreAtOnce() throws Exception {
        try {
            List<GenericValue> newValues = new LinkedList<GenericValue>();
            for (int i = 0; i < TEST_COUNT; i++) {
                newValues.add(delegator.makeValue("Testing", "testingId", getTestId("T1-", i)));
            }
            delegator.storeAll(newValues);
            List<GenericValue> newlyCreatedValues = delegator.findList("Testing", null, null, UtilMisc.toList("testingId"), null, false);
            assertEquals("Test to create " + TEST_COUNT + " and store all at once", TEST_COUNT, newlyCreatedValues.size());
        } finally {
            List<GenericValue> newlyCreatedValues = delegator.findList("Testing", null, null, UtilMisc.toList("testingId"), null, false);
            delegator.removeAll(newlyCreatedValues);
        }
    }

    /*
     * This test will create a large number of unique items and add them to the delegator at once
     */
    public void testCreateManyAndStoreOneAtATime() throws Exception {
        for (int i = 0; i < TEST_COUNT; i++) {
            delegator.create(delegator.makeValue("Testing", "testingId", getTestId("T2-", i)));
        }
        List<GenericValue> newlyCreatedValues = delegator.findList("Testing", null, null, UtilMisc.toList("testingId"), null, false);
        assertEquals("Test to create " + TEST_COUNT + " and store one at a time: ", TEST_COUNT, newlyCreatedValues.size());
    }

    /*
     * This test will use the large number of unique items from above and test the EntityListIterator looping through the list
     */
    public void testEntityListIterator() throws Exception {
        boolean beganTransaction = false;
        try {
            beganTransaction = TransactionUtil.begin();
            EntityListIterator iterator = delegator.find("Testing", EntityCondition.makeCondition("testingId", EntityOperator.LIKE, "T2-%"), null, null, UtilMisc.toList("testingId"), null);
            assertNotNull("Test if EntityListIterator was created: ", iterator);

            int i = 0;
            GenericValue item = iterator.next();
            while (item != null) {
                assertEquals("Testing if iterated data matches test data (row " + i + "): ", getTestId("T2-", i), item.getString("testingId"));
                item = iterator.next();
                i++;
            }
            assertEquals("Test if EntitlyListIterator iterates exactly " + TEST_COUNT + " times: " , TEST_COUNT, i);
            iterator.close();
        } catch (GenericEntityException e) {
            TransactionUtil.rollback(beganTransaction, "GenericEntityException occurred while iterating with EntityListIterator", e);
            assertTrue("GenericEntityException:" + e.toString(), false);
            return;
        } finally {
            TransactionUtil.commit(beganTransaction);
            List<GenericValue> entitiesToRemove = delegator.findList("Testing", EntityCondition.makeCondition("testingId", EntityOperator.LIKE, "T2-%"), null, null, null, false);
            delegator.removeAll(entitiesToRemove);
        }
    }

    /*
     * This test will verify transaction rollbacks using TransactionUtil.
     */
    public void testTransactionUtilRollback() throws Exception {
        GenericValue testValue = delegator.makeValue("Testing", "testingId", "rollback-test");
        boolean transBegin = TransactionUtil.begin();
        delegator.create(testValue);
        TransactionUtil.rollback(transBegin, null, null);
        GenericValue testValueOut = delegator.findOne("Testing", false, "testingId", "rollback-test");
        assertEquals("Test that transaction rollback removes value: ", testValueOut, null);
    }

    /*
     * This test will verify that a transaction which takes longer than the pre-set timeout are rolled back.
     */
    public void testTransactionUtilMoreThanTimeout() throws Exception {
        GenericTransactionException caught = null;
        try {
            GenericValue testValue = delegator.makeValue("Testing", "testingId", "timeout-test");
            boolean transBegin = TransactionUtil.begin(10); // timeout set to 10 seconds
            delegator.create(testValue);
            Thread.sleep(20*1000);
            TransactionUtil.commit(transBegin);
        } catch (GenericTransactionException e) {
            caught = e;
        } finally {
            assertNotNull("timeout thrown", caught);
            delegator.removeByAnd("Testing", "testingId", "timeout-test");
        }
    }

    /*
     * This test will verify that the same transaction transaction which takes less time than timeout will be committed.
     */
    public void testTransactionUtilLessThanTimeout() throws Exception {
        try {
            GenericValue testValue = delegator.makeValue("Testing", "testingId", "timeout-test");
            boolean transBegin = TransactionUtil.begin();
            TransactionUtil.setTransactionTimeout(20); // now set timeout to 20 seconds
            delegator.create(testValue);
            Thread.sleep(10*1000);
            TransactionUtil.commit(transBegin);
        } finally {
            delegator.removeByAnd("Testing", "testingId", "timeout-test");
        }
    }

    /*
     * Tests field types.
     */
    public void testFieldTypes() throws Exception {
        String id = "testFieldTypes";
        byte[] b = new byte[100000];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) i;
        }
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder sb = new StringBuilder(alpha.length() * 1000);
        for (int i = 0; i < 1000; i++) {
            sb.append(alpha);
        }
        String clobStr = sb.toString();
        long currentMillis = System.currentTimeMillis();
        Date currentDate = Date.valueOf(new Date(currentMillis).toString());
        Time currentTime = Time.valueOf(new Time(currentMillis).toString());
        // Different databases have different precision for Timestamps, so
        // we will ignore fractional seconds.
        Timestamp currentTimestamp = new Timestamp(currentDate.getTime());
        BigDecimal fixedPoint = new BigDecimal("999999999999.999999");
        // Different databases have different precision for floating
        // point types, so we will use a simple decimal number.
        Double floatingPoint = 1.0123456789;
        Long numeric = Long.MAX_VALUE;
        try {
            GenericValue testValue = delegator.makeValue("TestFieldType", "testFieldTypeId", id);
            testValue.create();
            testValue.set("blobField", b);
            testValue.set("byteArrayField", b);
            testValue.set("objectField", currentTimestamp);
            testValue.set("dateField", currentDate);
            testValue.set("timeField", currentTime);
            testValue.set("dateTimeField", currentTimestamp);
            testValue.set("fixedPointField", fixedPoint);
            testValue.set("floatingPointField", floatingPoint);
            testValue.set("numericField", numeric);
            testValue.set("clobField", clobStr);
            testValue.store();
            testValue = delegator.findOne("TestFieldType", UtilMisc.toMap("testFieldTypeId", id), false);
            assertEquals("testFieldTypeId", id, testValue.get("testFieldTypeId"));
            byte[] c = null;
            try {
                Blob blob = (Blob) testValue.get("blobField");
                c = blob.getBytes(1, (int) blob.length());
            } catch (ClassCastException e) {
                c = (byte[]) testValue.get("blobField");
            }
            assertEquals("Byte array read from entity is the same length", b.length, c.length);
            for (int i = 0; i < b.length; i++) {
                assertEquals("Byte array data[" + i + "]", b[i], c[i]);
            }
            c = (byte[]) testValue.get("byteArrayField");
            assertEquals("Byte array read from entity is the same length", b.length, c.length);
            for (int i = 0; i < b.length; i++) {
                assertEquals("Byte array data[" + i + "]", b[i], c[i]);
            }
            assertEquals("objectField", currentTimestamp, testValue.get("objectField"));
            assertEquals("dateField", currentDate, testValue.get("dateField"));
            assertEquals("timeField", currentTime, testValue.get("timeField"));
            assertEquals("dateTimeField", currentTimestamp, testValue.get("dateTimeField"));
            assertEquals("fixedPointField", fixedPoint, testValue.get("fixedPointField"));
            assertEquals("floatingPointField", floatingPoint, testValue.get("floatingPointField"));
            assertEquals("numericField", numeric, testValue.get("numericField"));
            assertEquals("clobField", clobStr, testValue.get("clobField"));
            testValue.set("blobField", null);
            testValue.set("byteArrayField", null);
            testValue.set("objectField", null);
            testValue.set("dateField", null);
            testValue.set("timeField", null);
            testValue.set("dateTimeField", null);
            testValue.set("fixedPointField", null);
            testValue.set("floatingPointField", null);
            testValue.set("numericField", null);
            testValue.set("clobField", null);
            testValue.store();
            testValue = delegator.findOne("TestFieldType", UtilMisc.toMap("testFieldTypeId", id), false);
            assertEquals("testFieldTypeId", id, testValue.get("testFieldTypeId"));
            assertNull("blobField null", testValue.get("blobField"));
            assertNull("byteArrayField null", testValue.get("byteArrayField"));
            assertNull("objectField null", testValue.get("objectField"));
            assertNull("dateField null", testValue.get("dateField"));
            assertNull("timeField null", testValue.get("timeField"));
            assertNull("dateTimeField null", testValue.get("dateTimeField"));
            assertNull("fixedPointField null", testValue.get("fixedPointField"));
            assertNull("floatingPointField null", testValue.get("floatingPointField"));
            assertNull("numericField null", testValue.get("numericField"));
            assertNull("clobField null", testValue.get("clobField"));
        } finally {
            // Remove all our newly inserted values.
            List<GenericValue> values = delegator.findList("TestFieldType", null, null, null, null, false);
            delegator.removeAll(values);
        }
    }

    /*
     * This creates an string id from a number
     */
    private String getTestId(String strTestBase, int iNum) {
        StringBuilder strBufTemp = new StringBuilder(strTestBase);
        if (iNum < 10000) {
           strBufTemp.append("0");
        }
        if (iNum < 1000) {
           strBufTemp.append("0");
        }
        if (iNum < 100) {
           strBufTemp.append("0");
        }
        if (iNum < 10) {
           strBufTemp.append("0");
        }
        strBufTemp.append(iNum);
        return strBufTemp.toString();
    }
    
    
    /*
     * This test will verify that the LIMIT and OFFSET options can work properly.
     * Commented out because it makes the framework dependent on the content component
     */
    /*public void testLimitOffsetOptions() throws Exception {
        String entityName = "Content";
        DatasourceInfo datasourceInfo = EntityConfigUtil.getDatasourceInfo(delegator.getEntityHelper(entityName).getHelperName());
        if (UtilValidate.isEmpty(datasourceInfo.offsetStyle) || datasourceInfo.offsetStyle.equals("none")) {
            Debug.logInfo("The offset-stype configured in datasource is " + datasourceInfo.offsetStyle +  ", this test is skipped.", module);
            return;
        } else {
            Debug.logInfo("The offset-stype configured in datasource is " + datasourceInfo.offsetStyle +  ".", module);
        }
        try {
            EntityFindOptions findOptions = new EntityFindOptions();
            long count = delegator.findCountByCondition("Content", null, null, null);
            Debug.logInfo("Content entity has " + count + " rows", module);
            int rowsPerPage = 10;
            // use rows/page as limit option
            findOptions.setLimit(rowsPerPage);
            int pages = (int) count/rowsPerPage;
            if (count > pages * rowsPerPage) {
                pages += 1;
            }
            Debug.logInfo("These rows will be displayed in " + pages + " pages, each page has " + rowsPerPage + " rows.", module);
            ModelEntity modelEntity = delegator.getModelEntity(entityName);

            long start = UtilDateTime.nowTimestamp().getTime();
            for (int page = 1; page <= pages; page++) {
                Debug.logInfo("Page " + page + ":", module);
                // set offset option
                findOptions.setOffset((page - 1) * rowsPerPage);
                EntityListIterator iterator = null;
                try {
                    iterator = delegator.getEntityHelper(entityName).findListIteratorByCondition(modelEntity, null, null, null, UtilMisc.toList("lastUpdatedStamp DESC"), findOptions);
                    while (iterator != null) {
                        GenericValue gv = iterator.next();
                        if (gv == null) {
                            break;
                        }
                        Debug.logInfo(gv.getString("contentId") + ": " + gv.getString("contentName") + "       (updated: " + gv.getTimestamp("lastUpdatedStamp") + ")", module);
                    }
                } catch (GenericEntityException e) {
                    Debug.logError(e, module);
                } finally {
                    if (iterator != null) {
                        iterator.close();
                    }
                }
            }
            long end = UtilDateTime.nowTimestamp().getTime();
            long time1 = end - start;
            Debug.logInfo("Time consumed WITH limit and offset option (ms): " + time1, module);
            
            start = UtilDateTime.nowTimestamp().getTime();
            for (int page = 1; page <= pages; page++) {
                Debug.logInfo("Page " + page + ":", module);
                EntityListIterator iterator = null;
                try {
                    iterator = ((GenericHelperDAO) delegator.getEntityHelper(entityName)).findListIteratorByCondition(modelEntity, null, null, null, UtilMisc.toList("lastUpdatedStamp DESC"), null);
                    if (iterator == null) {
                        continue;
                    }
                    iterator.setDelegator(delegator);
                    List<GenericValue> gvs = iterator.getCompleteList();
                    int fromIndex = (page - 1) * rowsPerPage;
                    int toIndex = fromIndex + rowsPerPage;
                    if (toIndex > count) {
                        toIndex = (int) count;
                    }
                    gvs = gvs.subList(fromIndex, toIndex);
                    for (GenericValue gv : gvs) {
                        Debug.logInfo(gv.getString("contentId") + ": " + gv.getString("contentName") + "       (updated: " + gv.getTimestamp("lastUpdatedStamp") + ")", module);
                    }
                } catch (GenericEntityException e) {
                    Debug.logError(e, module);
                } finally {
                    if (iterator != null) {
                        iterator.close();
                    }
                }
            }
            end = UtilDateTime.nowTimestamp().getTime();
            long time2 = end - start;
            Debug.logInfo("Time consumed WITHOUT limit and offset option (ms): " + time2, module);
            Debug.logInfo("Time saved (ms): " + (time2 - time1), module);
        } catch (GenericEntityException e) {
            Debug.logError(e, module);
        }
    }*/

    /*
     * Tests EntitySaxReader, verification loading data with tag create, create-update, create-replace, delete 
     */
    public void testEntitySaxReaderCreation() throws Exception {
        String xmlContentLoad = 
                "<TestingType testingTypeId=\"JUNIT-TEST\" description=\"junit test\"/>" +
                "<create>" +
                "    <TestingType testingTypeId=\"JUNIT-TEST2\" description=\"junit test\"/>" +
                "    <Testing testingId=\"T1\" testingTypeId=\"JUNIT-TEST\" testingName=\"First test\" testingSize=\"10\" testingDate=\"2010-01-01 00:00:00\"/>" +
                "</create>" +
                "<Testing testingId=\"T2\" testingTypeId=\"JUNIT-TEST2\" testingName=\"Second test\" testingSize=\"20\" testingDate=\"2010-02-01 00:00:00\"/>";
        EntitySaxReader reader = new EntitySaxReader(delegator);
        long numberLoaded = reader.parse(xmlContentLoad);
        assertEquals("Create Entity loaded ", numberLoaded, 4);
        GenericValue t1 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T1"), false);
        GenericValue t2 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T2"), true);
        assertNotNull("Create Testing(T1)", t1);
        assertEquals("Create Testing(T1).testingTypeId", "JUNIT-TEST", t1.getString("testingTypeId"));
        assertEquals("Create Testing(T1).testingName", "First test", t1.getString("testingName"));
        assertEquals("Create Testing(T1).testingSize", Long.valueOf(10), t1.getLong("testingSize"));
        assertEquals("Create Testing(T1).testingDate", UtilDateTime.toTimestamp("01/01/2010 00:00:00"), t1.getTimestamp("testingDate"));

        assertNotNull("Create Testing(T2)", t2);
        assertEquals("Create Testing(T2).testingTypeId", "JUNIT-TEST2", t2.getString("testingTypeId"));
        assertEquals("Create Testing(T2).testingName", "Second test", t2.getString("testingName"));
        assertEquals("Create Testing(T2).testingSize", Long.valueOf(20), t2.getLong("testingSize"));
        assertEquals("Create Testing(T2).testingDate", UtilDateTime.toTimestamp("02/01/2010 00:00:00"), t2.getTimestamp("testingDate"));
    }

    public void testEntitySaxReaderCreateSkip() throws Exception {
        String xmlContentLoad =
                "<create>" +
                "    <Testing testingId=\"T1\" testingName=\"First test update\" testingSize=\"20\"/>" +
                "</create>";
        EntitySaxReader reader = new EntitySaxReader(delegator);
        long numberLoaded = reader.parse(xmlContentLoad);
        assertEquals("Create Skip Entity loaded ", numberLoaded, 1);
        GenericValue t1 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T1"), false);
        assertNotNull("Create Skip Testing(T1)", t1);
        assertEquals("Create Skip Testing(T1).testingTypeId", "JUNIT-TEST", t1.getString("testingTypeId"));
        assertEquals("Create Skip Testing(T1).testingName", "First test", t1.getString("testingName"));
        assertEquals("Create Skip Testing(T1).testingSize", Long.valueOf(10), t1.getLong("testingSize"));
        assertEquals("Create Skip Testing(T1).testingDate", UtilDateTime.toTimestamp("01/01/2010 00:00:00"), t1.getTimestamp("testingDate"));
    }

    public void testEntitySaxReaderUpdate() throws Exception {
        String xmlContentLoad =
                "<create-update>" +
                "    <Testing testingId=\"T1\" testingName=\"First test update\" testingSize=\"20\"/>" +
                "    <Testing testingId=\"T3\" testingTypeId=\"JUNIT-TEST\" testingName=\"Third test\" testingSize=\"30\" testingDate=\"2010-03-01 00:00:00\"/>" +
                "</create-update>";
        EntitySaxReader reader = new EntitySaxReader(delegator);
        long numberLoaded = reader.parse(xmlContentLoad);
        assertEquals("Update Entity loaded ", numberLoaded, 2);
        GenericValue t1 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T1"), false);
        GenericValue t3 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T3"), false);
        assertNotNull("Update Testing(T1)", t1);
        assertEquals("Update Testing(T1).testingTypeId", "JUNIT-TEST", t1.getString("testingTypeId"));
        assertEquals("Update Testing(T1).testingName", "First test update", t1.getString("testingName"));
        assertEquals("Update Testing(T1).testingSize", Long.valueOf(20), t1.getLong("testingSize"));
        assertEquals("Update Testing(T1).testingDate", UtilDateTime.toTimestamp("01/01/2010 00:00:00"), t1.getTimestamp("testingDate"));

        assertNotNull("Update Testing(T3)", t3);
        assertEquals("Update Testing(T3).testingTypeId", "JUNIT-TEST", t3.getString("testingTypeId"));
        assertEquals("Update Testing(T3).testingName", "Third test", t3.getString("testingName"));
        assertEquals("Update Testing(T3).testingSize", Long.valueOf(30), t3.getLong("testingSize"));
        assertEquals("Update Testing(T3).testingDate", UtilDateTime.toTimestamp("03/01/2010 00:00:00"), t3.getTimestamp("testingDate"));
    }

    public void testEntitySaxReaderReplace() throws Exception {
        String xmlContentLoad =
                "<create-replace>" +
                "    <Testing testingTypeId=\"JUNIT-TEST\" testingId=\"T1\" testingName=\"First test replace\" />" +
                "</create-replace>" +
                "<Testing testingId=\"T2\" testingName=\"Second test update\"/>";
        EntitySaxReader reader = new EntitySaxReader(delegator);
        long numberLoaded = reader.parse(xmlContentLoad);
        assertEquals("Replace Entity loaded ", numberLoaded, 2);
        GenericValue t1 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T1"), false);
        GenericValue t2 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T2"), false);
        assertNotNull("Replace Testing(T1)", t1);
        assertEquals("Replace Testing(T1).testingTypeId", "JUNIT-TEST", t1.getString("testingTypeId"));
        assertEquals("Replace Testing(T1).testingName", "First test replace", t1.getString("testingName"));
        assertNull("Replace Testing(T1).testingSize", t1.getLong("testingSize"));
        assertNull("Replace Testing(T1).testingDate", t1.getTimestamp("testingDate"));

        assertNotNull("Replace Testing(T2)", t2);
        assertEquals("Replace Testing(T2).testingTypeId", "JUNIT-TEST2", t2.getString("testingTypeId"));
        assertEquals("Replace Testing(T2).testingName", "Second test update", t2.getString("testingName"));
        assertEquals("Replace Testing(T2).testingSize", Long.valueOf(20), t2.getLong("testingSize"));
        assertEquals("Replace Testing(T2).testingDate", UtilDateTime.toTimestamp("02/01/2010 00:00:00"), t2.getTimestamp("testingDate"));
    }

    public void testEntitySaxReaderDelete() throws Exception {
        String xmlContentLoad = 
                        "<delete>" +
                        "    <Testing testingId=\"T1\"/>" +
                        "    <Testing testingId=\"T2\"/>" +
                        "    <Testing testingId=\"T3\"/>" +
                        "    <TestingType testingTypeId=\"JUNIT-TEST\"/>" +
                        "    <TestingType testingTypeId=\"JUNIT-TEST2\"/>" +
                        "</delete>";
        EntitySaxReader reader = new EntitySaxReader(delegator);
        long numberLoaded = reader.parse(xmlContentLoad);
        assertEquals("Delete Entity loaded ", numberLoaded, 5);
        GenericValue t1 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T1"), false);
        GenericValue t2 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T2"), false);
        GenericValue t3 = delegator.findOne("Testing", UtilMisc.toMap("testingId", "T2"), false);
        assertNull("Delete Testing(T1)", t1);
        assertNull("Delete Testing(T2)", t2);
        assertNull("Delete Testing(T3)", t3);
        GenericValue testType = delegator.findOne("TestingType", UtilMisc.toMap("testingTypeId", "JUNIT-TEST"), false);
        assertNull("Delete TestingType 1", testType);
        testType = delegator.findOne("TestingType", UtilMisc.toMap("testingTypeId", "JUNIT-TEST2"), false);
        assertNull("Delete TestingType 2", testType);
    }
}
