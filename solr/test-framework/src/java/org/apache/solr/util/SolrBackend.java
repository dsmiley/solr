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
import org.apache.solr.client.api.model.CreateCollectionRequestBody;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.GenericSolrRequest;
import org.apache.solr.client.solrj.request.MetricsRequest;
import org.apache.solr.client.solrj.response.InputStreamResponseParser;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.embedded.JettySolrRunner;

/**
 * Abstraction over a running Solr deployment for use in tests and benchmarks. Implementations:
 * {@link org.apache.solr.cloud.MiniSolrCloudCluster} (embedded SolrCloud, multi-node), {@link
 * JettySolrRunner} (embedded Solr node HTTP server), {@link EmbeddedSolrBackend} (embedded Solr
 * node with native/direct SolrClient), {@link RemoteSolrBackend} (HTTP access to an externally
 * managed Solr).
 */
public interface SolrBackend extends AutoCloseable {

  /**
   * Creates a new {@link SolrClient} defaulted to the given collection. The <em>caller</em> owns
   * this client and is responsible for closing it. Callers that want a long-lived client should
   * cache it themselves.
   */
  SolrClient newClient(String collection);

  /**
   * Returns the admin (collection-less) {@link SolrClient} owned by this backend. Created eagerly
   * during backend initialization. The caller must NOT close it; it is released when this backend
   * is {@link #close()}d.
   */
  SolrClient getAdminClient();

  /**
   * Ensures a configset named {@code name} is registered on the server. Throws {@link
   * AlreadyExistsException} if a configset with that name already exists. For embedded/MiniCluster
   * implementations, uses {@code ConfigSetService} directly (no HTTP). For remote implementations,
   * uses zip + {@code ConfigSetAdminRequest.Upload}.
   *
   * <p>Must be called before {@link #createCollection}.
   *
   * @param configDir directory whose {@code conf/} subdirectory contains the configset files
   * @param name configset name to register
   */
  void registerConfigset(Path configDir, String name) throws SolrException, AlreadyExistsException;

  /** Convenience overload that derives the configset name from {@code configDir.getFileName()}. */
  default void registerConfigset(Path configDir) throws SolrException, AlreadyExistsException {
    registerConfigset(configDir, configDir.getFileName().toString());
  }

  /**
   * Creates a collection (or core for single-node backends). Cloud backends honour {@code
   * numShards} and {@code replicationFactor}; single-node backends ({@link JettySolrRunner}, {@link
   * EmbeddedSolrBackend}) ignore those fields.
   *
   * @throws AlreadyExistsException if the collection/core already exists.
   */
  void createCollection(CreateCollectionRequestBody body)
      throws AlreadyExistsException, SolrException;

  /**
   * Thrown by {@link #createCollection} and {@link #registerConfigset} when the named collection or
   * configset already exists. Callers can catch this to implement "create if absent" logic.
   */
  class AlreadyExistsException extends Exception {
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
   * Reloads a collection (typically to clear caches). For single-node implementations this reloads
   * the named core. Default implementation uses {@code CollectionAdminRequest.reloadCollection}.
   */
  default void reloadCollection(String name) throws SolrException {
    try {
      getAdminClient().request(CollectionAdminRequest.reloadCollection(name));
    } catch (SolrServerException | IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  /** Dumps Prometheus-format metrics to {@code out}. No-op is an acceptable implementation. */
  default void dumpMetrics(PrintStream out) throws SolrException {
    try {
      var request = new MetricsRequest();
      request.setResponseParser(new InputStreamResponseParser("prometheus"));
      var response = request.process(getAdminClient());
      out.println(InputStreamResponseParser.consumeResponseToString(response.getResponse()));
    } catch (SolrServerException | IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  /**
   * Dumps JSON core/collection info to {@code out}. No-op is an acceptable implementation. Default
   * uses {@code GET /admin/cores?indexInfo=true}.
   */
  default void dumpCoreInfo(PrintStream out) throws SolrException {
    try {
      var request =
          new GenericSolrRequest(
              SolrRequest.METHOD.GET, "/admin/cores", SolrParams.of("indexInfo", "true"));
      request.setResponseParser(new InputStreamResponseParser("json"));
      var response = request.process(getAdminClient());
      out.println(InputStreamResponseParser.consumeResponseToString(response.getResponse()));
    } catch (SolrServerException | IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  /** Shuts down this backend, releases the admin client, and all other resources. */
  @Override
  void close();
}
