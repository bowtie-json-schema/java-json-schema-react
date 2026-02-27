package bowtie;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.georgeakulov.json_schema.Schema;
import io.github.georgeakulov.json_schema.SchemaBuilder;
import io.github.georgeakulov.json_schema.common.JsonUtils;
import io.github.georgeakulov.json_schema.common.URIUtils;
import io.github.georgeakulov.json_schema.dialects.*;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import reactor.util.function.Tuples;

public class JsonSchemaValidator {

  private static final ObjectMapper MAPPER = new ObjectMapper().configure(
      DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  private static final Set<URI> SUPPORTED_DIALECTS = Set.of(
      Defaults.DIALECT_2020_12, Defaults.DIALECT_2019_09, Defaults.DIALECT_07);

  private static JsonNode preloadSpecifications(String name) {
    try {
      return new ObjectMapper().readTree(
          ClassLoader.getSystemClassLoader().getResourceAsStream(name));
    } catch (IOException e) {
      throw new RuntimeException(
          "Can`t preload the specifications from the classpath", e);
    }
  }

  private final PrintStream ps;
  private final Attributes attributes;
  private URI usedDialect;

  private JsonSchemaValidator(PrintStream ps) throws Exception {
    this.ps = ps;
    InputStream is = Objects.requireNonNull(
        getClass().getResourceAsStream("/META-INF/MANIFEST.MF"));
    this.attributes = new Manifest(is).getMainAttributes();
  }

  public static void main(String[] args) throws Exception {
    new JsonSchemaValidator(System.out)
        .loop(new BufferedReader(new InputStreamReader(System.in)));
  }

  private void loop(BufferedReader br) { br.lines().forEach(this::dispatch); }

  private void dispatch(String line) {
    try {

      JsonNode cmd = JsonUtils.parse(line);
      switch (cmd.path("cmd").asText()) {
      case "start" -> dispatchReq(cmd, StartReq.class, this::handleStart);
      case "dialect" -> dispatchReq(cmd, DialectReq.class, this::handleDialect);
      case "run" -> dispatchReq(cmd, RunReq.class, this::handlerRun);
      case "stop" -> System.exit(0);
      default ->
        throw new IllegalArgumentException("Unsupported command: " + cmd);
      }
    } catch (Exception e) {
      throw new RuntimeException("Exception while processing command: " + line,
                                 e);
    }
  }

  private StartRsp handleStart(StartReq req) {
    if (req.version != 1) {
      throw new IllegalStateException("Unsupported protocol version: " + req);
    }
    return new StartRsp(
        req.version,
        new Implementation(
            "java", attributes.getValue("Implementation-Name"),
            attributes.getValue("Implementation-Version"),
            SUPPORTED_DIALECTS.stream().map(Object::toString).toList(),
            "https://github.com/georgeakulov/json-schema",
            "https://github.com/georgeakulov/json-schema",
            "https://github.com/georgeakulov/json-schema/issues",
            "https://github.com/georgeakulov/json-schema"));
  }

  private DialectRsp handleDialect(DialectReq dialectReq) {
    usedDialect =
        URIUtils.clearEmptyFragments(URI.create(dialectReq.dialect()));
    return new DialectRsp(SUPPORTED_DIALECTS.contains(usedDialect));
  }

  private RunRsp handlerRun(RunReq req) {
    SchemaBuilder builder =
        SchemaBuilder.create().setDefaultDialect(usedDialect);

    // Apply registry if exists
    if (req.testCase.registry != null) {
      req.testCase.registry.forEachEntry(builder::addMappingIdToSchema);
    }

    Schema schema = builder.compile(req.testCase.schema);

    return new RunRsp(
        req.seq,
        req.testCase.tests.stream()
            .map(test -> new TestResult(schema.apply(test.instance).isOk()))
            .toList());
  }

  private <REQ> void dispatchReq(JsonNode node, Class<REQ> reqType,
                                 ExceptionableFn<REQ, Object> dispatcher) {
    try {
      REQ req = MAPPER.treeToValue(node, reqType);
      Object rsp = dispatcher.apply(req);
      ps.println(MAPPER.writeValueAsString(rsp));
      ps.flush();
    } catch (Throwable thr) {
      throw new RuntimeException(thr);
    }
  }

  private record StartReq(int version) {}
  private record StartRsp(int version, Implementation implementation) {}
  private record
      Implementation(String language, String name, String version,
                     List<String> dialects, String homepage,
                     String documentation, String issues, String source) {}

  private record DialectReq(String dialect) {}
  private record DialectRsp(boolean ok) {}

  private record RunReq(JsonNode seq, @JsonProperty("case") TestCase testCase) {
  }
  private record TestCase(String description, String comment, JsonNode schema,
                          JsonNode registry, List<Test> tests) {}
  private record Test(String description, String comment, JsonNode instance,
                      boolean valid) {}
  private record RunRsp(JsonNode seq, List<TestResult> results) {}
  private record TestResult(boolean valid) {}

  private interface ExceptionableFn<ARG, RET> {
    RET apply(ARG arg) throws Throwable;
  }
}
