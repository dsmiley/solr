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
package org.apache.solr.util;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.MetricsRequest;
import org.apache.solr.client.solrj.response.InputStreamResponseParser;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.embedded.JettySolrRunner;

/**
 * Abstraction over a running Solr deployment for use in tests and benchmarks. The abstraction
 * normalizes how to shut down and perform other common operations as the methods indicate.
 */
public interface SolrBackend extends AutoCloseable {

  /**
   * Creates a new {@link SolrClient} defaulted to the given collection. The <em>caller</em> owns
   * this client and is responsible for closing it. Callers that want a long-lived client should
   * cache it themselves.
   */
  SolrClient newClient(String collection);

  /**
   * Returns the admin (collection-less) {@link SolrClient} owned by this backend, to be used for
   * tasks that are not the subject of what is being tested, and thus the details of the type or
   * configuration of this client doesn't matter. The caller must NOT close it; it is released when
   * this backend is {@link #close()}d.
   */
  SolrClient getAdminClient();

  /**
   * <em>If</em> a configset by this name doesn't exist, this will upload it. Throws {@link
   * AlreadyExistsException} if a configset with that name already exists. Tests/benchmarks that
   * want to test how this works should not use this to do so.
   *
   * @param configDir directory that <em>directly</em> contains the configset files (no conf/).
   * @param name configset name to register
   */
  default void registerConfigset(Path configDir, String name) throws AlreadyExistsException {
    try {
      var ccs = getCoreContainer().getConfigSetService();
      if (ccs.checkConfigExists(name)) {
        throw new SolrBackend.AlreadyExistsException(name);
      }
      ccs.uploadConfig(name, configDir);
    } catch (SolrBackend.AlreadyExistsException | SolrException e) {
      throw e;
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  /**
   * Creates a collection (or core for single-node backends). Cloud backends honour {@code
   * numShards} and {@code replicationFactor}; single-node backends ({@link JettySolrRunner}, {@link
   * EmbeddedSolrBackend}) ignore those fields. Tests/benchmarks that want to test how this works
   * should not use this to do so.
   *
   * @throws AlreadyExistsException if the collection/core already exists.
   */
  void createCollection(CollectionAdminRequest.Create create) throws AlreadyExistsException;

  /**
   * Thrown by {@link #createCollection} and {@link #registerConfigset} when the named collection or
   * configset already exists. Callers can catch this to implement "create if absent" logic.
   */
  class AlreadyExistsException extends RuntimeException {
    public AlreadyExistsException(String name) {
      super(name + " already exists");
    }

    /**
     * Checks whether {@code e} indicates a resource already exists (HTTP 400 + "already exists" in
     * the message) and, if so, throws {@link AlreadyExistsException}. Otherwise returns normally so
     * the caller can re-throw {@code e}.
     */
    public static void rethrowIfAlreadyExists(SolrException e, String name)
        throws AlreadyExistsException {
      if (e.code() == SolrException.ErrorCode.BAD_REQUEST.code
          && e.getMessage().contains("already exists")) {
        throw new AlreadyExistsException(name);
      }
    }
  }

  /**
   * Provides access to an embedded/in-process {@link org.apache.solr.core.CoreContainer} -- if
   * available (else null). If there are more than one nodes, then one is returned.
   *
   * @return can be null.
   */
  CoreContainer getCoreContainer();

  /** Reloads a collection or core by this name. The purpose is typically to clear caches. */
  default void reloadCollection(String name) {
    try {
      getAdminClient().request(CollectionAdminRequest.reloadCollection(name));
    } catch (SolrServerException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Dumps Prometheus-format metrics to {@code out}. No-op is an acceptable implementation. */
  default void dumpMetrics(PrintStream out) {
    try {
      var request = new MetricsRequest();
      request.setResponseParser(new InputStreamResponseParser("prometheus"));
      var response = request.process(getAdminClient());
      out.println(InputStreamResponseParser.consumeResponseToString(response.getResponse()));
    } catch (SolrServerException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Dumps JSON-format information for all cores to {@code out}. No-op is an acceptable
   * implementation. Default uses {@code GET /admin/cores?indexInfo=true}.
   */
  default void dumpCoreInfo(PrintStream out) {
    try {
      var request =
          new GenericSolrRequest(
              SolrRequest.METHOD.GET, "/admin/cores", SolrParams.of("indexInfo", "true"));
      request.setResponseParser(new InputStreamResponseParser("json"));
      var response = request.process(getAdminClient());
      out.println(InputStreamResponseParser.consumeResponseToString(response.getResponse()));
    } catch (SolrServerException | IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Shuts down this backend, releases the admin client, and all other resources. */
  @Override
  void close();
}
