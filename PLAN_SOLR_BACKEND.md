# Plan: Replace SolrBenchBackend with a SolrBackend interface

## Context

`SolrBenchBackend` (SBB) and `SolrClientTestRule` (SCTR) serve overlapping purposes: both abstract over different Solr deployment modes. There is already a `// TODO should be replaced by SolrClientTestRule` comment in `SolrBenchBackend.java`. The goal is to eliminate SBB entirely by:
1. Introducing a clean `SolrBackend` interface (not a JUnit rule) that Solr's server classes implement directly
2. Making `SolrClientTestRule` a thin JUnit lifecycle adapter over `SolrBackend`
3. Having `SolrBenchState` use `SolrBackend` directly (no JUnit lifecycle baggage)

This resolves the awkwardness of using a JUnit `ExternalResource` in a JMH benchmark context.

---

## Architecture Overview

```
SolrBackend (interface, test-framework, extends AutoCloseable)
├── MiniSolrCloudCluster implements SolrBackend    [test-framework; add ~5 methods]
├── JettySolrRunner implements SolrBackend          [test-framework; add ~5 methods]
├── EmbeddedSolrBackend (new wrapper class)         [test-framework; wraps CoreContainer]
└── RemoteSolrBackend (new class)                   [test-framework; HTTP to remote Solr]

SolrClientTestRule extends ExternalResource          [test-framework; thin JUnit adapter]
  wraps a SolrBackend; does NOT implement SolrBackend; manages before()/after() lifecycle

SolrBenchState                                       [benchmark; uses SolrBackend directly]
  holds a SolrBackend — no JUnit lifecycle involved
```

**Module constraints:**
- `MiniSolrCloudCluster` and `JettySolrRunner` are in `solr/test-framework` → can directly implement a `test-framework` interface ✓
- `EmbeddedSolrServer` is in `solr/core`; `test-framework` depends on `core` (not the reverse) → `EmbeddedSolrServer` cannot implement a `test-framework` interface → use `EmbeddedSolrBackend` wrapper in `test-framework` instead

---

## Files

**New:**
- `solr/test-framework/src/java/org/apache/solr/util/SolrBackend.java`
- `solr/test-framework/src/java/org/apache/solr/util/EmbeddedSolrBackend.java`
- `solr/test-framework/src/java/org/apache/solr/util/RemoteSolrBackend.java`

**Modified:**
- `solr/test-framework/src/java/org/apache/solr/cloud/MiniSolrCloudCluster.java`
- `solr/test-framework/src/java/org/apache/solr/embedded/JettySolrRunner.java`
- `solr/test-framework/src/java/org/apache/solr/util/SolrClientTestRule.java`
- `solr/test-framework/src/java/org/apache/solr/util/EmbeddedSolrServerTestRule.java`
- `solr/test-framework/src/java/org/apache/solr/util/SolrJettyTestRule.java`
- `solr/benchmark/src/java/org/apache/solr/bench/SolrBenchState.java`

**Deleted:**
- `solr/benchmark/src/java/org/apache/solr/bench/SolrBenchBackend.java`
- `solr/benchmark/src/java/org/apache/solr/bench/MiniClusterBackend.java`
- `solr/benchmark/src/java/org/apache/solr/bench/EmbeddedSolrBackend.java`
- `solr/benchmark/src/java/org/apache/solr/bench/RemoteSolrBackend.java`
- `solr/benchmark/src/java/org/apache/solr/bench/SolrBenchBackendType.java`

---

## Step 1: Define `SolrBackend`

**Location:** `solr/test-framework/src/java/org/apache/solr/util/SolrBackend.java`

```java
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

/**
 * Abstraction over a running Solr deployment for use in tests and benchmarks.
 * Implementations: {@link MiniSolrCloudCluster} (embedded SolrCloud, multi-node),
 * {@link JettySolrRunner} (embedded Solr node HTTP server),
 * {@link EmbeddedSolrBackend} (embedded Solr node with native/direct SolrClient),
 * {@link RemoteSolrBackend} (HTTP access to an externally managed Solr).
 */
public interface SolrBackend extends AutoCloseable {

  /**
   * Creates a new {@link SolrClient} defaulted to the given collection.
   * The <em>caller</em> owns this client and is responsible for closing it.
   * Callers that want a long-lived client should cache it themselves.
   */
  SolrClient newClient(String collection);

  /**
   * Returns the admin (collection-less) {@link SolrClient} owned by this backend.
   * Created eagerly during backend initialization. The caller must NOT close it;
   * it is released when this backend is {@link #close()}d.
   */
  SolrClient getAdminClient();

  /**
   * Ensures a configset named {@code name} is registered on the server. Throws
   * {@link AlreadyExistsException} if a configset with that name already exists.
   * For embedded/MiniCluster implementations, uses {@code ConfigSetService} directly (no HTTP).
   * For remote implementations, uses zip + {@code ConfigSetAdminRequest.Upload}.
   *
   * <p>Must be called before {@link #createCollection}.
   *
   * @param configDir directory whose {@code conf/} subdirectory contains the configset files
   * @param name      configset name to register
   */
  void registerConfigset(Path configDir, String name) throws SolrException, AlreadyExistsException;

  /**
   * Convenience overload that derives the configset name from {@code configDir.getFileName()}.
   */
  default void registerConfigset(Path configDir) throws SolrException, AlreadyExistsException {
    registerConfigset(configDir, configDir.getFileName().toString());
  }

  /**
   * Creates a collection (or core for single-node backends). Throws {@link AlreadyExistsException}
   * if it already exists. Cloud backends ({@link MiniSolrCloudCluster}, {@link RemoteSolrBackend})
   * honour {@code numShards} and {@code replicationFactor}; single-node backends
   * ({@link JettySolrRunner}, {@link EmbeddedSolrBackend}) ignore those fields and treat
   * {@code body.config} as the configset name.
   */
  void createCollection(CreateCollectionRequestBody body)
      throws AlreadyExistsException, SolrException;

  /**
   * Thrown by {@link #createCollection} and {@link #registerConfigset} when the named
   * collection or configset already exists.
   * Callers can catch this to implement "create if absent" logic.
   */
  class AlreadyExistsException extends Exception {
    public AlreadyExistsException(String name) {
      super(name + " already exists");
    }
  }

  /**
   * Reloads a collection (typically to clear caches). For single-node implementations this
   * reloads the named core. Default implementation uses {@code CollectionAdminRequest.reloadCollection}.
   */
  default void reloadCollection(String name) throws SolrException {
    try {
      getAdminClient().request(CollectionAdminRequest.reloadCollection(name));
    } catch (SolrServerException | IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  /**
   * Dumps Prometheus-format metrics to {@code out}. No-op is an acceptable implementation.
   */
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
   * Dumps JSON core/collection info to {@code out}. No-op is an acceptable implementation.
   * Default uses {@code GET /admin/cores?indexInfo=true}.
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
```

---

## Step 2: `MiniSolrCloudCluster` implements `SolrBackend`

Already in `test-framework`. Already has `getSolrClient()` (no-arg admin client, eagerly created by the cluster), `dumpMetrics(PrintStream)`, `dumpCoreInfo(PrintStream)`.

**Add to class declaration:** `implements SolrBackend`

**Add methods:**

```java
@Override
public SolrClient newClient(String collection) {
  if (getJettySolrRunners().size() != 1) {
    return new CloudSolrClient.Builder(getSolrClient().getClusterStateProvider())
        .withDefaultCollection(collection).build();
  }
  return new HttpJettySolrClient.Builder(
          getJettySolrRunners().getFirst().getBaseUrl().toString())
      .withDefaultCollection(collection).build();
}

// getAdminClient: delegates to the cluster's internal admin client (already eagerly created)
@Override
public SolrClient getAdminClient() {
  return getSolrClient();
}

@Override
public void registerConfigset(Path configDir, String name)
    throws SolrException, SolrBackend.AlreadyExistsException {
  try {
    List<String> existing =
        new ConfigSetAdminRequest.List().process(getAdminClient()).getConfigSets();
    if (existing != null && existing.contains(name)) {
      throw new SolrBackend.AlreadyExistsException(name);
    }
    uploadConfigSet(configDir.resolve("conf"), name);  // existing cluster method
  } catch (AlreadyExistsException e) {
    throw e;
  } catch (Exception e) {
    throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
  }
}

@Override
public void createCollection(CreateCollectionRequestBody body)
    throws SolrBackend.AlreadyExistsException, SolrException {
  try {
    CollectionAdminResponse listResponse =
        new CollectionAdminRequest.List().process(getAdminClient());
    @SuppressWarnings("unchecked")
    List<String> existing = (List<String>) listResponse.getResponse().get("collections");
    if (existing != null && existing.contains(body.name)) {
      throw new SolrBackend.AlreadyExistsException(body.name);
    }
    CollectionAdminRequest.Create create =
        CollectionAdminRequest.createCollection(
            body.name, body.config,
            body.numShards != null ? body.numShards : 1,
            body.replicationFactor != null ? body.replicationFactor : 1);
    if (body.properties != null && !body.properties.isEmpty()) {
      create.setProperties(body.properties);
    }
    create.process(getAdminClient());
    int shards = body.numShards != null ? body.numShards : 1;
    int replicas = body.replicationFactor != null ? body.replicationFactor : 1;
    waitForActiveCollection(body.name, 15, TimeUnit.SECONDS, shards, shards * replicas);
  } catch (AlreadyExistsException e) {
    throw e;
  } catch (Exception e) {
    throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
  }
}

@Override
public void close() {
  try { shutdown(); } catch (Exception e) { throw new RuntimeException(e); }
  // shutdown() also closes the internal admin client
}

// dumpMetrics and dumpCoreInfo already exist — annotate @Override (they wrap exceptions as SolrException)
```

---

## Step 3: `JettySolrRunner` implements `SolrBackend`

Already in `test-framework`. The admin client is created eagerly when the runner has started (where the base URL is first available). Add field initialization at the point where the server is up (after `start()` completes or in the existing post-start hook).

**Add to class declaration:** `implements SolrBackend`

**Add field and methods:**

```java
// Add field — initialized eagerly in the existing post-start initialization:
private HttpSolrClient backendAdminClient;

// Called from existing start/init code once the server URL is available:
// backendAdminClient = new HttpSolrClient.Builder(getBaseUrl().toString()).build();

@Override
public SolrClient newClient(String collection) {
  return new HttpSolrClient.Builder(getBaseUrl().toString())
      .withDefaultCollection(collection).build();
}

@Override
public SolrClient getAdminClient() {
  return backendAdminClient;  // eagerly created; no null check or sync needed
}

@Override
public void registerConfigset(Path configDir, String name)
    throws SolrException, SolrBackend.AlreadyExistsException {
  try {
    var ccs = getCoreContainer().getConfigSetService();
    if (ccs.checkConfigExists(name)) {
      throw new SolrBackend.AlreadyExistsException(name);
    }
    ccs.uploadConfig(name, configDir.resolve("conf"));
  } catch (AlreadyExistsException e) {
    throw e;
  } catch (Exception e) {
    throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
  }
}

@Override
public void createCollection(CreateCollectionRequestBody body)
    throws SolrBackend.AlreadyExistsException, SolrException {
  // Single-node: numShards/replicationFactor ignored; body.config treated as configSet
  try {
    if (getCoreContainer().getCoreDescriptor(body.name) != null) {
      throw new SolrBackend.AlreadyExistsException(body.name);
    }
    CoreAdminRequest.Create req = new CoreAdminRequest.Create();
    req.setCoreName(body.name);
    req.setInstanceDir(body.name);
    if (body.config != null) req.setConfigSet(body.config);
    req.process(getAdminClient());
  } catch (AlreadyExistsException e) {
    throw e;
  } catch (Exception e) {
    throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
  }
}

@Override
public void close() {
  IOUtils.closeQuietly(backendAdminClient);
  try { stop(); } catch (Exception e) { throw new RuntimeException(e); }
}

// reloadCollection, dumpMetrics, dumpCoreInfo — use interface defaults
```

---

## Step 4: Create `EmbeddedSolrBackend`

**Location:** `solr/test-framework/src/java/org/apache/solr/util/EmbeddedSolrBackend.java`

```java
package org.apache.solr.util;

/**
 * {@link SolrBackend} backed by an in-process {@link CoreContainer}/{@link EmbeddedSolrServer}.
 * No network or ZooKeeper overhead. {@link CreateCollectionRequestBody} is not supported — use
 * {@link CreateCoreParams} for single-core creation.
 *
 * <p>Data is persisted in the given {@code solrHome} directory.
 */
public class EmbeddedSolrBackend implements SolrBackend {

  private final Path solrHome;
  private CoreContainer coreContainer;
  private EmbeddedSolrServer adminClient;

  public EmbeddedSolrBackend(Path solrHome) {
    this.solrHome = solrHome;
  }

  /**
   * Creates solrHome if necessary, writes a minimal solr.xml, loads the CoreContainer,
   * and initializes the admin client. Must be called before any other method.
   */
  public void start() throws Exception {
    if (!Files.exists(solrHome)) {
      Files.createDirectories(solrHome);
      Files.writeString(solrHome.resolve("solr.xml"),
          "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<solr/>\n");
      Files.createDirectories(solrHome.resolve("configsets"));
    }
    coreContainer = new CoreContainer(solrHome, new Properties());
    coreContainer.load();
    adminClient = new EmbeddedSolrServer(coreContainer, null);  // eager; no null check later
  }

  @Override
  public SolrClient newClient(String collection) {
    return new EmbeddedSolrServer(coreContainer, collection);
  }

  @Override
  public SolrClient getAdminClient() {
    return adminClient;  // eagerly created in start()
  }

  @Override
  public void registerConfigset(Path configDir, String name)
      throws SolrException, SolrBackend.AlreadyExistsException {
    Path targetConfDir = solrHome.resolve("configsets").resolve(name).resolve("conf");
    if (Files.exists(targetConfDir)) {
      throw new SolrBackend.AlreadyExistsException(name);
    }
    try {
      Path sourceConfDir = configDir.resolve("conf");
      Files.createDirectories(targetConfDir);
      try (var stream = Files.walk(sourceConfDir)) {
        stream.forEach(source -> {
          try {
            Path target = targetConfDir.resolve(sourceConfDir.relativize(source));
            if (Files.isDirectory(source)) Files.createDirectories(target);
            else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
          } catch (IOException e) { throw new RuntimeException(e); }
        });
      }
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  @Override
  public void createCollection(CreateCollectionRequestBody body)
      throws SolrBackend.AlreadyExistsException, SolrException {
    // Single-node: numShards/replicationFactor ignored; body.config treated as configSet
    try {
      if (coreContainer.getCoreDescriptor(body.name) != null) {
        throw new SolrBackend.AlreadyExistsException(body.name);
      }
      Map<String, String> coreParams = new HashMap<>();
      if (body.config != null) coreParams.put("configSet", body.config);
      if (body.properties != null) coreParams.putAll(body.properties);
      coreContainer.create(body.name, coreParams);
    } catch (AlreadyExistsException e) {
      throw e;
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  @Override
  public void reloadCollection(String name) throws SolrException {
    try { coreContainer.reload(name); }
    catch (Exception e) { throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e); }
  }

  @Override
  public void close() {
    IOUtils.closeQuietly(adminClient);
    if (coreContainer != null) coreContainer.shutdown();
  }

  // dumpMetrics, dumpCoreInfo — use interface defaults
}
```

---

## Step 5: Create `RemoteSolrBackend`

**Location:** `solr/test-framework/src/java/org/apache/solr/util/RemoteSolrBackend.java`

```java
package org.apache.solr.util;

/**
 * {@link SolrBackend} that connects to a pre-existing remote Solr node.
 * The caller supplies the base URL at construction time (e.g. {@code http://host:port/solr}).
 *
 * <p>Can be subclassed to add a startup step (e.g. launching a Docker container) before
 * constructing the URL.
 */
public class RemoteSolrBackend implements SolrBackend {

  protected final String baseUrl;
  private final HttpJettySolrClient adminClient;  // eager; created in constructor

  public RemoteSolrBackend(String baseUrl) {
    this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl must not be null");
    this.adminClient = new HttpJettySolrClient.Builder(baseUrl).build();
  }

  @Override
  public SolrClient newClient(String collection) {
    return new HttpJettySolrClient.Builder(baseUrl).withDefaultCollection(collection).build();
  }

  @Override
  public SolrClient getAdminClient() {
    return adminClient;
  }

  @Override
  public void registerConfigset(Path configDir, String name)
      throws SolrException, SolrBackend.AlreadyExistsException {
    try {
      List<String> existing =
          new ConfigSetAdminRequest.List().process(adminClient).getConfigSets();
      if (existing != null && existing.contains(name)) {
        throw new SolrBackend.AlreadyExistsException(name);
      }
      Path tempZip = Files.createTempFile("configset-", ".zip");
      try {
        zipDirectory(configDir.resolve("conf"), tempZip);
        new ConfigSetAdminRequest.Upload()
            .setConfigSetName(name)
            .setUploadFile(tempZip, "application/zip")
            .process(adminClient);
      } finally {
        Files.deleteIfExists(tempZip);
      }
    } catch (AlreadyExistsException e) {
      throw e;
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  @Override
  public void createCollection(CreateCollectionRequestBody body)
      throws SolrBackend.AlreadyExistsException, SolrException {
    try {
      CollectionAdminResponse listResponse =
          new CollectionAdminRequest.List().process(adminClient);
      @SuppressWarnings("unchecked")
      List<String> existing = (List<String>) listResponse.getResponse().get("collections");
      if (existing != null && existing.contains(body.name)) {
        throw new SolrBackend.AlreadyExistsException(body.name);
      }
      CollectionAdminRequest.Create create =
          CollectionAdminRequest.createCollection(
              body.name, body.config,
              body.numShards != null ? body.numShards : 1,
              body.replicationFactor != null ? body.replicationFactor : 1);
      if (body.properties != null && !body.properties.isEmpty()) {
        create.setProperties(body.properties);
      }
      create.process(adminClient);
      // No wait — remote cluster manages its own readiness
    } catch (AlreadyExistsException e) {
      throw e;
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
  }

  /** Packages {@code sourceDir} contents into a zip file. */
  private static void zipDirectory(Path sourceDir, Path targetZip) throws IOException {
    try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(targetZip))) {
      Files.walk(sourceDir)
          .filter(Files::isRegularFile)
          .forEach(file -> {
            try {
              zos.putNextEntry(new ZipEntry(sourceDir.relativize(file).toString()));
              Files.copy(file, zos);
              zos.closeEntry();
            } catch (IOException e) { throw new RuntimeException(e); }
          });
    }
  }

  @Override
  public void close() {
    IOUtils.closeQuietly(adminClient);
  }

  // reloadCollection, dumpMetrics, dumpCoreInfo — use interface defaults
}
```

---

## Step 6: Refactor `SolrClientTestRule` as a concrete delegation wrapper

`SolrClientTestRule` becomes a **concrete class** wrapping a `SolrBackend`. It does **not** implement `SolrBackend`. `NewCollectionBuilder` stays here.

```java
/**
 * JUnit {@link ExternalResource} that manages a {@link SolrBackend} lifecycle.
 * Use {@link #getBackend()} to access the underlying backend for full API access.
 * Convenience methods are provided for the most common operations.
 */
public class SolrClientTestRule extends ExternalResource {

  private final SolrBackend backend;

  public SolrClientTestRule(SolrBackend backend) {
    this.backend = backend;
  }

  @Override
  protected void after() {
    backend.close();
  }

  /** Returns the underlying {@link SolrBackend} for full API access. */
  public SolrBackend getBackend() { return backend; }

  /** @see SolrBackend#newClient(String) — caller owns the returned client */
  public SolrClient newClient(String collection) { return backend.newClient(collection); }

  /** @see SolrBackend#getAdminClient() — backend-owned; do not close */
  public SolrClient getAdminClient() { return backend.getAdminClient(); }

  public NewCollectionBuilder newCollection(String name) { return new NewCollectionBuilder(name); }

  public NewCollectionBuilder newCollection() {
    return new NewCollectionBuilder(DEFAULT_TEST_COLLECTION_NAME);
  }

  public void clearIndex() throws SolrServerException, IOException {
    new UpdateRequest().deleteByQuery("*:*").commit(backend.getAdminClient(), null);
  }

  protected void create(NewCollectionBuilder b) throws SolrException, AlreadyExistsException {
    var body = new CreateCollectionRequestBody();
    body.name = b.getName();
    body.config = b.getConfigSet();  // single-node backends treat this as configSet
    body.numShards = b.getNumShards();
    body.replicationFactor = b.getReplicationFactor();
    backend.createCollection(body);
  }

  public class NewCollectionBuilder {
    private final String name;
    private String configSet;
    private String configFile;
    private String schemaFile;
    private String basicAuthUser;
    private String basicAuthPwd;
    private int numShards = 1;
    private int replicationFactor = 1;

    public NewCollectionBuilder(String name) { this.name = name; }

    public NewCollectionBuilder withConfigSet(String configSet) {
      // Chop off "/conf" suffix if present — see existing comment in SolrClientTestRule
      if (configSet != null) {
        final var sep = FileSystems.getDefault().getSeparator();
        final var confSuffix = sep + "conf";
        if (configSet.endsWith(confSuffix))
          configSet = configSet.substring(0, configSet.length() - confSuffix.length());
        this.configSet = configSet;
      }
      return this;
    }

    public NewCollectionBuilder withConfigSet(Path configSet) { return withConfigSet(configSet.toString()); }
    public NewCollectionBuilder withConfigFile(String configFile) { this.configFile = configFile; return this; }
    public NewCollectionBuilder withSchemaFile(String schemaFile) { this.schemaFile = schemaFile; return this; }
    public NewCollectionBuilder withBasicAuthCredentials(String user, String pwd) { this.basicAuthUser = user; this.basicAuthPwd = pwd; return this; }
    public NewCollectionBuilder withNumShards(int numShards) { this.numShards = numShards; return this; }
    public NewCollectionBuilder withReplicationFactor(int replicationFactor) { this.replicationFactor = replicationFactor; return this; }

    public String getName() { return name; }
    public String getConfigSet() { return configSet; }
    public String getConfigFile() { return configFile; }
    public String getSchemaFile() { return schemaFile; }
    public String getBasicAuthUser() { return basicAuthUser; }
    public String getBasicAuthPwd() { return basicAuthPwd; }
    public int getNumShards() { return numShards; }
    public int getReplicationFactor() { return replicationFactor; }

    public void create() throws SolrException, AlreadyExistsException {
      SolrClientTestRule.this.create(this);
    }
  }
}
```

**`before()`**: Not overridden. Callers start the backend before constructing `SolrClientTestRule`
(for `EmbeddedSolrBackend`, call `start()` explicitly before passing it in).

**`EmbeddedSolrServerTestRule` and `SolrJettyTestRule`**: These existing subclasses can be
**deprecated** in favor of `new SolrClientTestRule(new EmbeddedSolrBackend(...))`, or
**kept** to expose backend-specific extras: `getCoreContainer()`, `getJetty()`, `enableProxy()`.

**`basicAuthCredentials`**: `CreateCoreParams` has no basic-auth fields; that's an HTTP concern.
The `basicAuthUser/Pwd` fields remain on `NewCollectionBuilder` as a no-op for now; a follow-up can address.

---

## Step 7: Update `SolrBenchState`

Field type change: `SolrBenchBackend backend` → `SolrBackend backend`

**In `start()` — backend instantiation:**
```java
// MINICLUSTER:
//   backend = new MiniSolrCloudCluster.Builder(nodeCount, indexDir).formatZkServer(false).build();
//   (MiniSolrCloudCluster now implements SolrBackend; admin client already eagerly created by cluster)
// EMBEDDED:
//   var embedded = new EmbeddedSolrBackend(indexDir);
//   embedded.start();
//   backend = embedded;
// REMOTE:
//   backend = new RemoteSolrBackend(System.getProperty("solr.bench.url"));
//   // system property read here, in benchmark layer — not inside RemoteSolrBackend
```

**`createCollection()` in `SolrBenchState` — uses `AlreadyExistsException` instead of boolean return:**
```java
public boolean createCollection(String configName, Map<String, String> properties) throws SolrException {
  boolean created;
  try {
    var body = new CreateCollectionRequestBody();
    body.name = collection;
    body.config = configName;
    body.numShards = numShards;
    body.replicationFactor = numReplicas;
    if (!properties.isEmpty()) body.properties = properties;
    backend.createCollection(body);
    created = true;
  } catch (SolrBackend.AlreadyExistsException e) {
    created = false;
  }
  benchmarkClient = backend.newClient(collection);  // SolrBenchState owns and closes this
  if (!created) log("Using EXISTING collection: " + collection);
  return created;
}
```

**Other changes:**
- `backend.getClient(collection)` → `backend.newClient(collection)` (cached as `benchmarkClient`)
- `getBackend()` return type → `SolrBackend`
- `shutdown()`: `IOUtils.closeQuietly(benchmarkClient)` + `backend.close()` (no checked exceptions)
- `logIndexDirSize` check: `backend instanceof MiniSolrCloudCluster`
- `forceMerge(int maxSegments)` and `waitForMerges()` are inlined using `benchmarkClient`:
  ```java
  new UpdateRequest().setAction(OPTIMIZE, false, true, maxSegments).process(benchmarkClient);
  ```

---

## Issues and Notes

1. **Single `createCollection` overload**: All backends implement `createCollection(CreateCollectionRequestBody)`.
   Single-node backends (`JettySolrRunner`, `EmbeddedSolrBackend`) use `body.config` as the configSet name
   and ignore `numShards`/`replicationFactor`. `SCTR.create()` and `SolrBenchState` both build a
   `CreateCollectionRequestBody` unconditionally — no `instanceof` branching needed.  Furthermore, the implementatiosn should optimistcally assume they can create, and catch an exception to see if the response code & message together indicate it already exists, and then throw the AlreadyExistsException.

3. **`MiniSolrCloudCluster.close()` vs `shutdown()`**: The cluster currently has `shutdown()` but not `close()`.
   Adding `close()` satisfies `AutoCloseable` and enables try-with-resources.

4. **`JettySolrRunner.close()` vs `stop()`**: Same pattern — `close()` delegates to `stop()` and closes
   the cached admin client.

5. **`JettySolrRunner` admin client initialization**: `backendAdminClient` is created once the server URL
   is available, in the existing post-start initialization path (not lazily, not synchronized).

6. **`registerConfigset` now throws `AlreadyExistsException`**: Existing callers that expect a no-op when
   the configset exists must catch `AlreadyExistsException`. Update `SolrBenchState` call site accordingly.  All "embedded" implementations have a CoreContainer that has a ConfigSetService -- this is what will be used.  Only the remote implementation requires HTTP upload zip.

7. **`SolrClientTestRule` no longer abstract, no longer implements `SolrBackend`**: It is a JUnit lifecycle
   wrapper only. Tests needing the full `SolrBackend` API call `rule.getBackend()`.

8. **`solr.bench.url` system property**: Read in `SolrBenchState.start()` and passed to
   `RemoteSolrBackend(url)`. The system-property concern stays in the benchmark layer.

---

## Verification

- `./gradlew -p solr/test-framework assemble` — new interface and classes compile
- `./gradlew -p solr/benchmark assemble` — `SolrBenchState` compiles against `SolrBackend`
- `./gradlew -p solr/test-framework test` — existing `EmbeddedSolrServerTestRule`- and `SolrJettyTestRule`-based tests still pass
- Run a JMH benchmark against each of MINICLUSTER, EMBEDDED, REMOTE backends
- `./gradlew precommit` — code style and license headers

## Work Sequencing

Modifications involving SolrClientTestRule and its subclasses shall be deferred to a future step/PR.
