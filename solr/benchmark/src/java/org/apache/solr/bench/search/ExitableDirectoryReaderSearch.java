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
package org.apache.solr.bench.search;

import static org.apache.solr.bench.BaseBenchState.log;
import static org.apache.solr.bench.generators.SourceDSL.integers;
import static org.apache.solr.bench.generators.SourceDSL.strings;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.solr.bench.Docs;
import org.apache.solr.bench.SolrBenchState;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.search.CallerSpecificQueryLimit;
import org.apache.solr.util.TestInjection;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@Fork(value = 1)
@BenchmarkMode(Mode.AverageTime)
@Warmup(time = 20, iterations = 2)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Measurement(time = 30, iterations = 4)
@Threads(value = 1)
public class ExitableDirectoryReaderSearch {

  @State(Scope.Benchmark)
  public static class BenchState {

    Docs queryFields;

    int NUM_DOCS = 500_000;
    int WORDS = NUM_DOCS / 100;

    @Setup(Level.Trial)
    public void setupTrial(SolrBenchState solrBenchState) throws Exception {

      solrBenchState.start(1, 1, 1);
      if (solrBenchState.createCollection(
          "cloud-minimal",
          Map.of(
              "documentCache.enabled", "false",
              "queryResultCache.enabled", "false",
              "filterCache.enabled", "false",
              "segmentsPerTier", "200",
              "maxBufferedDocs", "100"))) {
        log("######### Creating index ...");
        // create a lot of large-ish fields to scan positions
        Docs docs =
            Docs.docs(1234567890L)
                .field("id", integers().incrementing())
                .field("f1_ts", strings().alpha().maxCardinality(WORDS).ofLengthBetween(3, 10))
                .field(
                    "f2_ts",
                    strings().alpha().maxCardinality(WORDS).multi(50).ofLengthBetween(3, 10))
                .field(
                    "f3_ts",
                    strings().alpha().maxCardinality(WORDS).multi(50).ofLengthBetween(3, 10))
                .field(
                    "f4_ts",
                    strings().alpha().maxCardinality(WORDS).multi(50).ofLengthBetween(3, 10))
                .field(
                    "f5_ts",
                    strings().alpha().maxCardinality(WORDS).multi(50).ofLengthBetween(3, 10))
                .field(
                    "f6_ts",
                    strings().alpha().maxCardinality(WORDS).multi(50).ofLengthBetween(3, 10))
                .field(
                    "f7_ts",
                    strings().alpha().maxCardinality(WORDS).multi(50).ofLengthBetween(3, 10))
                .field(
                    "f8_ts",
                    strings().alpha().maxCardinality(WORDS).multi(50).ofLengthBetween(3, 10))
                .field(
                    "f9_ts",
                    strings().alpha().maxCardinality(WORDS).multi(50).ofLengthBetween(3, 10));
        solrBenchState.index(docs, NUM_DOCS, true);
        solrBenchState.forceMerge(200);
      }
      solrBenchState.dumpCoreInfo();
    }

    // this adds significant processing time to the checking of query limits
    // both to verify that it's actually used and to illustrate the impact of limit checking
    @Param({"false", "true"})
    boolean verifyEDRInUse = true;

    private static final String matchExpression = "ExitableTermsEnum:-1";

    @Setup(Level.Iteration)
    public void setupQueries(SolrBenchState solrBenchState) throws Exception {
      if (verifyEDRInUse) {
        TestInjection.queryTimeout = new CallerSpecificQueryLimit(Set.of(matchExpression));
      }
      // reload collection to force searcher / reader refresh
      solrBenchState.reloadCollection();

      queryFields =
          Docs.docs(1234567890L)
              .field("id", integers().incrementing())
              .field("f1_ts", strings().alpha().maxCardinality(WORDS).ofLengthBetween(3, 10))
              .field(
                  "f2_ts", strings().alpha().maxCardinality(WORDS).multi(5).ofLengthBetween(3, 10));
    }

    @TearDown(Level.Iteration)
    public void tearDownTrial() throws Exception {
      if (verifyEDRInUse) {
        CallerSpecificQueryLimit queryLimit = (CallerSpecificQueryLimit) TestInjection.queryTimeout;
        if (queryLimit == null) {
          throw new RuntimeException("Missing setup!");
        }
        Map<String, Integer> callCounts = queryLimit.getCallerMatcher().getCallCounts();
        log("######### Caller specific stats:");
        log("Call counts: " + callCounts);
        if (callCounts.get(matchExpression) == null) {
          throw new RuntimeException("Missing call counts!");
        }
        if (callCounts.get(matchExpression).intValue() == 0) {
          throw new RuntimeException("No call counts!");
        }
      }
    }
  }

  private static ModifiableSolrParams createInitialParams() {
    return SolrBenchState.params("rows", "100", "timeAllowed", "1000", "fl", "*");
  }

  @Benchmark
  public void testShortQuery(SolrBenchState solrBenchState, Blackhole bh, BenchState state)
      throws Exception {
    SolrInputDocument queryDoc = state.queryFields.inputDocument();
    ModifiableSolrParams params = createInitialParams();
    params.set("q", "f1_ts:" + queryDoc.getFieldValue("f1_ts").toString());
    QueryRequest queryRequest = new QueryRequest(params);
    QueryResponse rsp = queryRequest.process(solrBenchState.getClient());
    bh.consume(rsp);
  }

  @Benchmark
  public void testLongQuery(SolrBenchState solrBenchState, Blackhole bh, BenchState state)
      throws Exception {
    SolrInputDocument queryDoc = state.queryFields.inputDocument();
    ModifiableSolrParams params = createInitialParams();
    StringBuilder query = new StringBuilder();
    for (int i = 2; i < 10; i++) {
      if (query.length() > 0) {
        query.append(" ");
      }
      String fld = "f" + i + "_ts";
      query.append(fld + ":\"" + queryDoc.getFieldValue(fld) + "\"~20");
    }
    params.set("q", query.toString());
    QueryRequest queryRequest = new QueryRequest(params);
    QueryResponse rsp = queryRequest.process(solrBenchState.getClient());
    bh.consume(rsp);
  }
}
