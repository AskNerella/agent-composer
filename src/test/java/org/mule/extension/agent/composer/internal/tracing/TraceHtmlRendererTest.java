package org.mule.extension.agent.composer.internal.tracing;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TraceHtmlRendererTest {

    @Test
    public void indexGroupsConversationExecutionsAndFiltersWhileTyping() {
        ExecutionTrace first = trace("exec-2", "corr-1", "Second request", "COMPLETED", 20L);
        ExecutionTrace second = trace("exec-1", "corr-1", "First request", "INPUT_REQUIRED", 10L);

        String html = TraceHtmlRenderer.renderIndex(Arrays.asList(first, second), "/agent/traces");

        assertTrue(html.contains("2 executions"));
        assertFalse(html.contains("Second request"));
        assertFalse(html.contains("First request"));
        assertTrue(html.contains("<span>ID</span><span>Model</span>"));
        assertTrue(html.contains("<strong>gpt-4o</strong><small>OPENAI</small>"));
        assertFalse(html.contains("Correlation ID &middot;"));
        assertTrue(html.contains("oninput=\"filterRuns()\""));
        assertTrue(html.contains("class=\"filters\""));
        assertTrue(html.contains("class=\"trace-search\""));
        assertTrue(html.contains("class=\"custom-select\""));
        assertTrue(html.contains("chooseStatus(this)"));
        assertFalse(html.contains("<select id=\"statusFilter\""));
        assertFalse(html.contains("toggleFilters()"));
        assertTrue(html.contains("[hidden]{display:none!important}"));
        assertTrue(html.contains("clearTraces(this)"));
    }

    @Test
    public void detailProvidesMcpDrawerAndStandardPermissionActionsWithoutVisibleRequestOrCopyIdentifier() {
        ExecutionTrace trace = trace("exec-1", "corr-1", "Delete ASK-53", "INPUT_REQUIRED", 10L);
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", "Linear MCP");
        server.put("url", "https://example.test/mcp");
        Map<String, Object> describedTool = new LinkedHashMap<>();
        describedTool.put("name", "get_issue");
        describedTool.put("description", "Retrieve an issue by identifier");
        server.put("tools", Arrays.asList(describedTool, "delete_issue"));
        trace.getAttributes().put("mcpServers", Collections.singletonList(server));
        trace.getAttributes().put("a2aTaskId", "task-1");
        trace.getAttributes().put("a2aContextId", "ctx-1");
        trace.getAttributes().put("agentPath", "/agent");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputKind", "tool_permission");
        result.put("permissionRequestId", "permission-1");
        trace.getAttributes().put("result", result);

        String html = TraceHtmlRenderer.render(trace, "/agent/traces");

        assertFalse(html.contains("class=\"user-request\""));
        assertFalse(html.contains("title=\"Copy identifier\""));
        assertTrue(html.contains("Waiting for Customer"));
        assertTrue(html.contains("id=\"mcpDrawer\""));
        assertTrue(html.contains("Search tools..."));
        assertTrue(html.contains("class=\"tool-list-item\""));
        assertTrue(html.contains("Retrieve an issue by identifier"));
        assertTrue(html.contains("No description provided."));
        assertTrue(html.contains("openMcpDrawer(){const d=document.getElementById('mcpDrawer')"));
        assertTrue(html.contains("openDetailSidebar();details.hidden=true"));
        assertTrue(html.contains("submitPermission('approve'"));
        assertTrue(html.contains("method:'message/send'"));
        assertTrue(html.contains("class=\"notification-count\">1"));
        assertTrue(html.contains("document.querySelectorAll('.notification-count').forEach(x=>x.remove())"));
        assertFalse(html.contains("<section class=\"runs-card mcp-inventory\""));
        assertFalse(html.contains(">Collapse</button>"));
    }

    @Test
    public void resolvedPermissionShowsDecisionAndWaitWithoutOfferingButtonsForThatStep() {
        ExecutionTrace trace = trace("exec-2", "corr-1", "Delete ASK-53", "COMPLETED", 10L);
        TraceSpan waiting = new TraceSpan();
        waiting.setSpanId("wait-span");
        waiting.setType("ITERATION");
        waiting.setName("Iteration 1");
        waiting.setStatus("INPUT_REQUIRED");
        Map<String, Object> permission = new LinkedHashMap<>();
        permission.put("inputKind", "tool_permission");
        permission.put("permissionRequestId", "permission-1");
        waiting.setOutput(permission);

        TraceSpan approved = new TraceSpan();
        approved.setSpanId("approval-span");
        approved.setType("TOOL_PERMISSION");
        approved.setName("Approve delete_issue");
        approved.setStatus("COMPLETED");
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("permissionRequestId", "permission-1");
        decision.put("decision", "approve");
        decision.put("approvalWaitMs", 2450L);
        approved.setInput(decision);
        trace.setSpans(Arrays.asList(waiting, approved));

        String html = TraceHtmlRenderer.render(trace, "/agent/traces");

        assertTrue(html.contains("Tool Approval Wait"));
        assertTrue(html.contains("2.45 s"));
        assertTrue(html.contains("Approved"));
        assertTrue(html.contains("actions.hidden=!permission||!!decision"));
        assertTrue(html.contains("class=\"detail-card detail-drawer open\""));
        assertTrue(html.contains("selectSpan(-1,document.querySelector"));
        assertTrue(html.contains("onclick=\"closeDetailSidebar()\""));
        assertFalse(html.contains("id=\"detailSidebarToggle\""));
        assertTrue(html.contains("sidebar-closed"));
        assertTrue(html.contains("transition:grid-template-columns"));
        assertTrue(html.contains("class=\"trace-page\""));
        assertTrue(html.contains("class=\"trace-content\""));
        assertTrue(html.contains("height:calc(100vh - 68px)"));
        assertTrue(html.contains("overflow-y:auto"));
        assertTrue(html.contains("Back to traces"));
        assertFalse(html.contains("class=\"notification-count\""));
        assertFalse(html.contains(">Events</button>"));
        assertTrue(html.contains("-webkit-line-clamp:2"));
        assertTrue(html.contains("border-bottom:1px solid var(--border-soft)"));
        assertFalse(html.contains("class=\"trace-layout\""));
        assertTrue(html.contains("a.querySelectorAll('button').forEach(x=>x.hidden=true)"));
        assertFalse(html.contains("collapseTree()"));
        assertFalse(html.contains(">Collapse</button>"));
    }

    private static ExecutionTrace trace(String executionId, String correlationId, String task,
                                        String status, long startedAt) {
        ExecutionTrace trace = new ExecutionTrace();
        trace.setExecutionId(executionId);
        trace.setConversationId(correlationId);
        trace.setUserTask(task);
        trace.setStatus(status);
        trace.setProvider("OPENAI");
        trace.setModelName("gpt-4o");
        trace.setStartedAtEpochMs(startedAt);
        trace.setDurationMs(5L);
        return trace;
    }
}
