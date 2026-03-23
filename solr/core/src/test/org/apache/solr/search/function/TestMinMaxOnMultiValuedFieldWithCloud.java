/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.search.function;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Locale;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SolrCloud test for SOLR-12457: Asserts that sorting on multi-valued fields using {@code
 * field(fieldname,min|max)} works correctly in a distributed (2-shard) context, for both deprecated
 * {@link org.apache.solr.schema.TrieIntField} and modern {@link
 * org.apache.solr.schema.IntPointField} types.
 *
 * <p>The original bug was a {@link ClassCastException} thrown in {@link
 * org.apache.solr.handler.component.ShardFieldSortedHitQueue} when merging sort values across
 * shards for trie-based multi-valued integer fields, because the field's sort comparator expected
 * {@link org.apache.lucene.util.BytesRef} values but received {@link String} values instead.
 *
 * @see <a href="https://issues.apache.org/jira/browse/SOLR-12457">SOLR-12457</a>
 */
public class TestMinMaxOnMultiValuedFieldWithCloud extends SolrCloudTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String COLLECTION =
      MethodHandles.lookup().lookupClass().getSimpleName().toLowerCase(Locale.ROOT);

  /** A multi-valued trie integer field (uses SortedSetDocValues). */
  private static final String TRIE_INT_FIELD = "vals_tis";

  /** A multi-valued point integer field (uses SortedNumericDocValues). */
  private static final String POINT_INT_FIELD = "vals_is_p";

  /** Number of shards — must be at least 2 to trigger distributed sort merging. */
  private static final int NUM_SHARDS = 2;

  private static CloudSolrClient CLOUD_CLIENT;

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster(NUM_SHARDS)
        .addConfig("conf", configset("cloud-sorting-multivalue"))
        .configure();

    CollectionAdminRequest.createCollection(COLLECTION, "conf", NUM_SHARDS, 1)
        .process(cluster.getSolrClient());

    cluster.waitForActiveCollection(COLLECTION, NUM_SHARDS, NUM_SHARDS);

    CLOUD_CLIENT = cluster.getSolrClient();

    // Index 3 documents distributed across 2 shards, with distinct max/min values:
    //   doc "1": multi-values [1, 5]  → min=1, max=5
    //   doc "2": multi-values [2, 4]  → min=2, max=4
    //   doc "3": multi-values [3, 3]  → min=3, max=3
    // Distributing 3 docs across 2 shards ensures the distributed sort merge is exercised.
    final int[][] docValues = {{1, 5}, {2, 4}, {3, 3}};
    final UpdateRequest req = new UpdateRequest();
    for (int id = 1; id <= 3; id++) {
      SolrInputDocument doc = new SolrInputDocument();
      doc.addField("id", String.valueOf(id));
      for (int v : docValues[id - 1]) {
        doc.addField(TRIE_INT_FIELD, v);
        doc.addField(POINT_INT_FIELD, v);
      }
      req.add(doc);
    }
    req.commit(CLOUD_CLIENT, COLLECTION);
  }

  @AfterClass
  public static void afterClass() {
    CLOUD_CLIENT = null;
  }

  /**
   * Tests that sorting by {@code field(field,max) desc} and {@code field(field,min) asc} works
   * correctly for a multi-valued <em>trie</em> integer field in a distributed context.
   *
   * <p>SOLR-12457 reported a {@link ClassCastException} for trie fields. This test verifies whether
   * that bug is present or has been resolved.
   */
  @Test
  public void testSortByFieldMinMaxOnTrieIntField() throws Exception {
    assertSortByFieldMinMaxWorks(TRIE_INT_FIELD);
  }

  /**
   * Tests that sorting by {@code field(field,max) desc} and {@code field(field,min) asc} works
   * correctly for a multi-valued <em>points</em> integer field in a distributed context.
   */
  @Test
  public void testSortByFieldMinMaxOnPointIntField() throws Exception {
    assertSortByFieldMinMaxWorks(POINT_INT_FIELD);
  }

  /**
   * Asserts that sorting by {@code field(fieldName,max) desc/asc} and {@code field(fieldName,min)
   * desc/asc} returns all 3 documents in the correct order in a distributed query against {@link
   * #NUM_SHARDS} shards.
   *
   * <p>Document max/min values:
   *
   * <ul>
   *   <li>doc "1": values [1,5] → min=1, max=5
   *   <li>doc "2": values [2,4] → min=2, max=4
   *   <li>doc "3": values [3,3] → min=3, max=3
   * </ul>
   */
  private void assertSortByFieldMinMaxWorks(String fieldName) throws Exception {
    // sort by max desc: doc1(max=5), doc2(max=4), doc3(max=3)
    assertDistributedSortOrder(fieldName, "max", "desc", "1", "2", "3");
    // sort by max asc: doc3(max=3), doc2(max=4), doc1(max=5)
    assertDistributedSortOrder(fieldName, "max", "asc", "3", "2", "1");
    // sort by min asc: doc1(min=1), doc2(min=2), doc3(min=3)
    assertDistributedSortOrder(fieldName, "min", "asc", "1", "2", "3");
    // sort by min desc: doc3(min=3), doc2(min=2), doc1(min=1)
    assertDistributedSortOrder(fieldName, "min", "desc", "3", "2", "1");
  }

  private void assertDistributedSortOrder(
      String fieldName, String selector, String dir, String... expectedDocIds) throws Exception {
    final String sort = "field(" + fieldName + "," + selector + ") " + dir;
    log.info("Testing distributed sort: {} on field {}", sort, fieldName);

    QueryResponse rsp =
        CLOUD_CLIENT.query(
            COLLECTION,
            params(
                "q", "*:*",
                "fl", "id",
                "sort", sort,
                "rows", "10"));

    assertNotNull("null response for sort=" + sort, rsp);
    List<SolrDocument> docs = rsp.getResults();
    assertEquals(
        "Wrong doc count for sort=" + sort + " on field " + fieldName,
        expectedDocIds.length,
        docs.size());
    for (int i = 0; i < expectedDocIds.length; i++) {
      assertEquals(
          "Wrong doc at position "
              + i
              + " for sort="
              + sort
              + " on field "
              + fieldName
              + "; full result="
              + docs,
          expectedDocIds[i],
          docs.get(i).getFieldValue("id").toString());
    }
  }
}
