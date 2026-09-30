package io.quarkiverse.mcp.server.test.dummyinit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.http.runtime.StreamableHttpMcpMessageHandler;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpServerTest;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.RestAssured;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * Verify that a server name unknown at build time uses the HTTP configuration of the default server, so that
 * {@code auto-init} applies to it, while a server configured at build time keeps its own configuration.
 */
public class AutoInitRuntimeServerNameTest extends McpServerTest {

    @RegisterExtension
    static final QuarkusUnitTest config = defaultConfig()
            .withApplicationRoot(
                    root -> root.addClasses(MyTools.class, RuntimeServerRoute.class))
            .overrideConfigKey("quarkus.mcp.server.invalid-server-name-strategy", "ignore")
            .overrideConfigKey("quarkus.mcp.server.http.streamable.auto-init", "true")
            .overrideConfigKey("quarkus.mcp.server.bravo.http.root-path", "/bravo/mcp");

    @Inject
    ToolManager toolManager;

    @Test
    public void testRuntimeServerNameUsesDefaultServerConfig() {
        toolManager.newTool("echo")
                .setServerName("tenant-a")
                .setDescription("echo")
                .setHandler(ta -> ToolResponse.success("tenant-a"))
                .register();
        try {
            // A session that is unknown to this instance, e.g. opened on another replica
            JsonObject response = new JsonObject(postWithUnknownSession("/tenants/tenant-a", "echo")
                    .then()
                    .statusCode(200)
                    .extract().body().asString());
            JsonObject result = response.getJsonObject("result");
            assertFalse(result.getBoolean("isError"));
            assertEquals("tenant-a", result.getJsonArray("content").getJsonObject(0).getString("text"));
        } finally {
            toolManager.removeTool("echo", "tenant-a");
        }
    }

    @Test
    public void testConfiguredServerKeepsItsOwnConfig() {
        // "bravo" is known at build time and does not enable auto-init
        postWithUnknownSession("/bravo/mcp", "bravo")
                .then()
                .statusCode(404);
    }

    private static io.restassured.response.Response postWithUnknownSession(String path, String toolName) {
        return RestAssured.given()
                .when()
                .headers(StreamableHttpMcpMessageHandler.MCP_SESSION_ID_HEADER, "nonExistentSession",
                        HttpHeaders.ACCEPT + "", "application/json, text/event-stream")
                .body(new JsonObject()
                        .put("jsonrpc", "2.0")
                        .put("method", McpAssured.TOOLS_CALL)
                        .put("id", 1)
                        .put("params", new JsonObject().put("name", toolName))
                        .encode())
                .post(path);
    }

    public static class MyTools {

        @Tool
        @McpServer("bravo")
        String bravo() {
            return "bravo";
        }

    }

    @Singleton
    public static class RuntimeServerRoute {

        // Serves server names that only exist at runtime, e.g. /tenants/tenant-a
        void init(@Observes Router router, StreamableHttpMcpMessageHandler handler) {
            router.post("/tenants/:name")
                    .handler(BodyHandler.create())
                    .handler(ctx -> {
                        // HttpMcpServerRecorder.CONTEXT_KEY
                        ctx.put("mcp.http.server-name", ctx.pathParam("name"));
                        handler.handle(ctx);
                    });
        }

    }

}
