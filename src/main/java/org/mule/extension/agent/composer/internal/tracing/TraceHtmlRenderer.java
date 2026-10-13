package org.mule.extension.agent.composer.internal.tracing;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Produces the trace console, Mermaid execution diagram, and waterfall timeline. */
public final class TraceHtmlRenderer {
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter
            .ofPattern("MMM d, uuuu h:mm:ss a", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private TraceHtmlRenderer() {}

    public static String render(ExecutionTrace trace, String reportPath) {
        if (trace == null) return notFound();
        String safePath = escapeAttribute(reportPath != null ? reportPath : "traces");
        boolean running = ExecutionTraceContext.STATUS_RUNNING.equals(trace.getStatus());
        String logoPath = escapeAttribute(brandLogoPath(reportPath));
        String identityLabel = traceIdentityLabel(trace);
        String identityValue = traceIdentityValue(trace);
        StringBuilder rows = new StringBuilder();
        StringBuilder tableRows = new StringBuilder();
        Map<String, Integer> depths = calculateDepths(trace.getSpans());

        rows.append("<button class=\"span-row root-row\" data-span-index=\"-1\" onclick=\"selectSpan(-1,this)\">"
                + "<span class=\"span-label\">").append(statusIcon(trace.getStatus()))
                .append("<span><b>Agent Run</b><small>")
                .append(escape(trace.getProvider())).append(" &middot; ").append(escape(trace.getModelName()))
                .append("</small></span></span><span class=\"track\"><i class=\"bar root-bar\" style=\"left:0;width:100%\"></i></span><strong class=\"row-duration\">")
                .append(formatDuration(trace.getDurationMs())).append("</strong></button>");

        for (int i = 0; i < trace.getSpans().size(); i++) {
            TraceSpan span = trace.getSpans().get(i);
            int depth = Math.min(depths.getOrDefault(span.getSpanId(), 0) + 1, 8);
            double left = percent(Math.max(0, span.getStartedAtEpochMs() - trace.getStartedAtEpochMs()), trace.getDurationMs());
            double width = Math.max(1.2, percent(Math.max(1, span.getDurationMs()), trace.getDurationMs()));
            if (left + width > 100) width = Math.max(0.8, 100 - left);
            rows.append("<button class=\"span-row\" data-span-index=\"").append(i)
                    .append("\" onclick=\"selectSpan(").append(i).append(",this)\">"
                    + "<span class=\"span-label\" style=\"--depth:").append(depth).append("\">")
                    .append(statusIcon(span.getStatus())).append("<span><b>")
                    .append(escape(displaySpanName(span))).append("</b><small>")
                    .append(escape(span.getType()));
            if (span.getIteration() != null) rows.append(" &middot; iteration ").append(span.getIteration());
            rows.append("</small></span></span><span class=\"track\"><i class=\"bar type-")
                    .append(typeClass(span.getType())).append("\" style=\"left:")
                    .append(formatPercent(left)).append("%;width:").append(formatPercent(width))
                    .append("%\"></i></span><strong class=\"row-duration\">")
                    .append(formatDuration(span.getDurationMs())).append("</strong></button>");

            tableRows.append("<tr tabindex=\"0\" data-span-index=\"").append(i)
                    .append("\" onclick=\"selectSpan(").append(i).append(",this)\" onkeydown=\"activateRow(event,")
                    .append(i).append(",this)\"><td><span class=\"status ")
                    .append(statusClass(span.getStatus())).append("\">")
                    .append(statusIcon(span.getStatus())).append(escape(span.getStatus())).append("</span></td><td><span class=\"kind\">")
                    .append(icon(iconForType(span.getType()))).append(escape(span.getType())).append("</span></td><td><strong>")
                    .append(escape(displaySpanName(span))).append("</strong></td><td><code class=\"cell-preview\" title=\"")
                    .append(escapeAttribute(compact(span.getInput(), 500))).append("\">").append(escape(compact(span.getInput(), 76)))
                    .append("</code></td><td><code class=\"cell-preview\" title=\"")
                    .append(escapeAttribute(compact(span.getOutput(), 500))).append("\">").append(escape(compact(span.getOutput(), 76)))
                    .append("</code></td><td><span class=\"cell-error")
                    .append(span.getErrorMessage() == null || span.getErrorMessage().isEmpty() ? "" : " has-error")
                    .append("\" title=\"")
                    .append(escapeAttribute(nullSafeCell(span.getErrorMessage()))).append("\">")
                    .append(escape(nullSafeCell(span.getErrorMessage()))).append("</span></td><td><time>")
                    .append(escape(displayTime(span.getStartedAtEpochMs()))).append("</time></td><td><strong class=\"latency\">")
                    .append(formatDuration(span.getDurationMs())).append("</strong></td></tr>");
        }

        String traceJson = safeScriptJson(trace);
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"icon\" href=\"" + logoPath + "\">"
                + "<title>Agent Trace " + escape(trace.getExecutionId()) + "</title>" + themeBootstrap() + "<style>" + css() + themeCss() + "</style></head><body>"
                + "<nav class=\"topbar\"><a class=\"brand\" href=\"" + safePath + "\"><img src=\"" + logoPath + "\" alt=\"\"><strong>Agent Composer</strong></a>"
                + icon("chevron-right") + "<a class=\"breadcrumb-link\" href=\"" + safePath + "\">Traces</a><i>/</i><code>"
                + escape(identityValue) + "</code><div class=\"top-actions\">"
                + "<button class=\"icon-button\" onclick=\"copyText(document.querySelector('.topbar code').textContent)\" title=\"Copy identifier\" aria-label=\"Copy identifier\">" + icon("copy") + "</button>"
                + "<a class=\"icon-button\" href=\"" + safePath + "?executionId=" + escapeAttribute(trace.getExecutionId())
                + "&amp;format=json\" title=\"Export JSON\" aria-label=\"Export JSON\">" + icon("download") + "</a><button class=\"icon-button\" onclick=\"location.reload()\" title=\"Refresh\" aria-label=\"Refresh\">" + icon("refresh-cw") + "</button>"
                + themeToggle() + "<a class=\"icon-button\" href=\"" + safePath + "\" title=\"Close trace\" aria-label=\"Close trace\">" + icon("x") + "</a></div></nav>"
                + "<main><section class=\"run-summary\"><div class=\"summary-title\"><div><h1>" + escape(identityLabel) + " &middot; " + escape(identityValue)
                + "</h1><p>Agent trace execution details</p></div><span class=\"status "
                + statusClass(trace.getStatus()) + "\">" + (running ? "<i class=\"pulse\"></i>" : "")
                + statusIcon(trace.getStatus()) + escape(trace.getStatus()) + "</span></div><div class=\"summary-grid\">"
                + summaryItem("Provider / Model", nullSafe(trace.getProvider()) + " / " + nullSafe(trace.getModelName()))
                + summaryItem("Started", displayTime(trace.getStartedAtEpochMs()))
                + summaryItem("Duration", formatDuration(trace.getDurationMs()))
                + summaryItem("Tokens", trace.getTotalTokens() + " (" + trace.getInputTokens() + " in / " + trace.getOutputTokens() + " out)")
                + summaryItem("Retries", String.valueOf(trace.getRetryCount()))
                + summaryItem("Iteration Limit", String.valueOf(trace.getMaxIterations())) + "</div></section>"
                + alert(trace.getError(), "error-banner", "Execution error")
                + alert(trace.getPersistenceError(), "warning-banner", "Persistence warning")
                + "<section class=\"trace-layout\"><div class=\"timeline-card\"><header class=\"card-header\"><div><h2>Trace Timeline</h2><p>"
                + formatDuration(trace.getDurationMs()) + " total duration &middot; " + trace.getSpans().size()
                + " spans</p></div><div class=\"view-actions\"><div class=\"view-toggle\"><button class=\"active\" onclick=\"switchView('waterfall',this)\">" + icon("gantt-chart") + "Waterfall</button><button onclick=\"switchView('table',this)\">" + icon("table-2") + "Span table</button></div><button class=\"button collapse-button\" onclick=\"collapseTree()\">" + icon("list-tree") + "Collapse</button></div></header>"
                + "<div id=\"loopDiagram\" class=\"loop-diagram\" aria-label=\"Agent iteration flow diagram\"></div>"
                + "<div id=\"waterfallView\" class=\"trace-view active\"><div class=\"scale\"><span></span><div><i>0ms</i><i>25%</i><i>50%</i><i>75%</i><i>100%</i></div><b></b></div><div id=\"spanRows\">"
                + rows + "</div></div><div id=\"tableView\" class=\"trace-view span-table-wrap\"><table class=\"span-table\"><thead><tr><th>Status</th><th>Kind</th><th>Name</th><th>Input</th><th>Output</th><th>Error</th><th>Start Time</th><th>Latency</th></tr></thead><tbody>"
                + tableRows + "</tbody></table></div></div></section>"
                + "<aside id=\"detailDrawer\" class=\"detail-card detail-drawer\" aria-hidden=\"true\"><header class=\"detail-header\"><div><span id=\"detailType\">EXECUTION</span><h2 id=\"detailName\">Agent Run</h2></div><div class=\"drawer-actions\"><span id=\"detailStatus\" class=\"status\"></span><button class=\"icon-button drawer-close\" onclick=\"closeDrawer()\" title=\"Close details\" aria-label=\"Close details\">" + icon("x") + "</button></div></header>"
                + "<div class=\"detail-tabs\"><button class=\"active\" onclick=\"showTab('details',this)\">Details</button><button onclick=\"showTab('request',this)\">Request</button><button onclick=\"showTab('response',this)\">Response</button><button onclick=\"showTab('events',this)\">Events</button></div>"
                + "<div id=\"tab-details\" class=\"tab-panel active\"><div id=\"detailMetrics\" class=\"detail-metrics\"></div><h3>Identifiers</h3><div id=\"detailIds\" class=\"kv\"></div></div>"
                + "<div id=\"tab-request\" class=\"tab-panel\"><div class=\"code-head\"><h3>Request / Input</h3><button onclick=\"copyPanel('requestJson')\">Copy</button></div><pre id=\"requestJson\"></pre></div>"
                + "<div id=\"tab-response\" class=\"tab-panel\"><div class=\"code-head\"><h3>Response / Output</h3><button onclick=\"copyPanel('responseJson')\">Copy</button></div><pre id=\"responseJson\"></pre></div>"
                + "<div id=\"tab-events\" class=\"tab-panel\"><div class=\"code-head\"><h3>Events & Attributes</h3><button onclick=\"copyPanel('eventsJson')\">Copy</button></div><pre id=\"eventsJson\"></pre></div>"
                + "</aside></main><script src=\"https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js\"></script><script>const trace=" + traceJson + ";" + script(running) + "</script></body></html>";
    }

    public static String renderIndex(List<ExecutionTrace> traces, String reportPath) {
        String safePath = escapeAttribute(reportPath != null ? reportPath : "traces");
        String logoPath = escapeAttribute(brandLogoPath(reportPath));
        StringBuilder rows = new StringBuilder();
        for (ExecutionTrace trace : traces) {
            String identityLabel = traceIdentityLabel(trace);
            String identityValue = traceIdentityValue(trace);
            rows.append("<a class=\"run-row\" data-status=\"").append(escapeAttribute(statusClass(trace.getStatus())))
                    .append("\" data-search=\"").append(escapeAttribute((nullSafe(trace.getExecutionId()) + " "
                            + nullSafe(trace.getConversationId()) + " " + nullSafe(trace.getUserTask()) + " "
                            + nullSafe(trace.getModelName())).toLowerCase(Locale.ROOT)))
                    .append("\" href=\"").append(escapeAttribute(reportPath)).append("?executionId=")
                    .append(escapeAttribute(trace.getExecutionId())).append("\"><span><strong>")
                    .append(escape(identityLabel)).append(" &middot; ").append(escape(identityValue)).append("</strong><small>")
                    .append(escape(trace.getProvider())).append(" / ").append(escape(trace.getModelName()))
                    .append("</small></span><span class=\"status ").append(statusClass(trace.getStatus())).append("\">")
                    .append(statusIcon(trace.getStatus())).append(escape(trace.getStatus())).append("</span><b>")
                    .append(formatDuration(trace.getDurationMs())).append("</b><time>")
                    .append(escape(displayTime(trace.getStartedAtEpochMs()))).append("</time>")
                    .append(icon("chevron-right")).append("</a>");
        }
        if (rows.length() == 0) rows.append("<div class=\"empty\">No execution traces yet.</div>");
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><link rel=\"icon\" href=\"" + logoPath + "\">"
                + "<title>Agent Traces</title>" + themeBootstrap() + "<style>" + css() + themeCss() + "</style></head><body>"
                + "<nav class=\"topbar\"><a class=\"brand\" href=\"" + safePath + "\"><img src=\"" + logoPath + "\" alt=\"\"><strong>Agent Composer</strong></a>" + icon("chevron-right") + "<span>Traces</span><div class=\"top-actions\"><button class=\"icon-button\" onclick=\"location.reload()\" title=\"Refresh\" aria-label=\"Refresh\">" + icon("refresh-cw") + "</button>" + themeToggle() + "</div></nav><main class=\"index-main\">"
                + "<header class=\"index-title\"><div><h1>Agent Traces</h1><p>Inspect and debug your AI agent executions, from input to final response.</p></div>"
                + "</header>"
                + "<section class=\"filters\"><label class=\"search-field\">" + icon("search") + "<input id=\"search\" placeholder=\"Search by correlation ID, model, or task...\" oninput=\"filterRuns()\"></label>"
                + "<select id=\"statusFilter\" onchange=\"filterRuns()\"><option value=\"\">All statuses</option><option value=\"completed\">Completed</option>"
                + "<option value=\"running\">Running</option><option value=\"input-required\">Input required</option><option value=\"failed\">Failed</option></select></section>"
                + "<section class=\"runs-card\"><div class=\"run-head\"><span>Correlation / Trace ID</span><span>Status</span><span>Duration</span><span>Started at</span><i></i></div>"
                + rows + "</section></main><script>" + themeScript()
                + "function filterRuns(){const q=document.getElementById('search').value.toLowerCase();const s=document.getElementById('statusFilter').value;document.querySelectorAll('.run-row').forEach(r=>r.hidden=!(r.dataset.search.includes(q)&&(!s||r.dataset.status===s)));}</script></body></html>";
    }

    private static String script(boolean running) {
        return "const spans=trace.spans||[];const root={spanId:trace.executionId,parentSpanId:null,type:'EXECUTION',name:'Agent Run',status:trace.status,startedAt:trace.startedAt,endedAt:trace.endedAt,durationMs:trace.durationMs,input:trace.userTask,output:(trace.attributes||{}).result,inputTokens:trace.inputTokens,outputTokens:trace.outputTokens,retryCount:trace.retryCount,errorMessage:trace.error,attributes:trace.attributes||{},events:[]};"
                + "function esc(v){return v==null?'-':String(v)}function html(v){return esc(v).replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]))}function pretty(v){return v==null?'No data captured.':JSON.stringify(v,null,2)}"
                + "function metric(k,v){return '<div><span>'+html(k)+'</span><strong>'+html(v)+'</strong></div>'}function kv(k,v){return '<span>'+html(k)+'</span><code>'+html(v)+'</code>'}"
                + themeScript()
                + "function mermaidLabel(v){return String(v||'').replace(/[\"\\n\\r<>{}]/g,' ').trim()}"
                + "function buildLoopDiagram(){const iterations=spans.filter(s=>s.type==='ITERATION');const lines=['flowchart LR','U[\"User Input\"]','class U input'];let previous='U';iterations.forEach((it,idx)=>{const n=it.iteration||idx+1;const entryFrom=previous;const tools=spans.filter(s=>(s.type==='MCP_TOOL'||s.type==='SKILL')&&s.iteration===n&&(!s.parentSpanId||s.parentSpanId===it.spanId));lines.push('subgraph I'+n+'[\"ITERATION '+n+'\"]','direction TB','L'+n+'[\"LLM<br/><small>Plan</small>\"]','class L'+n+' llm');if(tools.length){tools.forEach((t,j)=>{const id='T'+n+'_'+j;lines.push(id+'[\"'+mermaidLabel(t.name)+'\"]','class '+id+' tool','L'+n+' --> '+id)});if(tools.length===1){previous='T'+n+'_0'}else{const join='J'+n;lines.push(join+'(( ))','class '+join+' join');tools.forEach((t,j)=>lines.push('T'+n+'_'+j+' --> '+join));previous=join}}else{previous='L'+n}lines.push('end',entryFrom+' --> L'+n)});if(iterations.length&&trace.status==='COMPLETED'){lines.push('A[\"Answer\"]','class A answer',previous+' --> A')}lines.push('classDef input fill:#f8fafc,stroke:#64748b,color:#0f172a','classDef llm fill:#eef2ff,stroke:#6366f1,color:#312e81','classDef tool fill:#ecfdf5,stroke:#0d9488,color:#134e4a','classDef answer fill:#dcfce7,stroke:#16a34a,color:#14532d','classDef join fill:#94a3b8,stroke:#64748b,color:#64748b');return lines.join('\\n')}"
                + "async function renderLoopDiagram(){const el=document.getElementById('loopDiagram');if(!el)return;if(!window.mermaid){el.innerHTML='<span class=\"diagram-unavailable\">Diagram library unavailable.</span>';return}try{mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:document.documentElement.classList.contains('dark')?'dark':'default',flowchart:{curve:'stepAfter',htmlLabels:true}});const rendered=await mermaid.render('agentLoopDiagram',buildLoopDiagram());el.innerHTML=rendered.svg}catch(e){el.innerHTML='<span class=\"diagram-unavailable\">Unable to render execution diagram.</span>'}}renderLoopDiagram();"
                + "function selectSpan(i,row){document.querySelectorAll('[data-span-index]').forEach(x=>x.classList.remove('selected'));document.querySelectorAll('[data-span-index=\"'+i+'\"]').forEach(x=>x.classList.add('selected'));const s=i<0?root:spans[i];document.getElementById('detailType').textContent=s.type||'SPAN';const displayName=s.type==='ITERATION'?String(s.name||'').replace(/^ReAct /i,''):s.name;document.getElementById('detailName').textContent=displayName||'Unnamed span';const st=document.getElementById('detailStatus');st.textContent=s.status||'UNKNOWN';st.className='status '+String(s.status||'').toLowerCase().replaceAll('_','-');document.getElementById('detailMetrics').innerHTML=metric('Latency',formatMs(s.durationMs))+metric('Tokens',(s.inputTokens||0)+(s.outputTokens||0))+metric('Input tokens',s.inputTokens||0)+metric('Output tokens',s.outputTokens||0)+metric('Retries',s.retryCount||0)+metric('Iteration',s.iteration??'-')+metric('Input size',sizeOf(s.input))+metric('Output size',sizeOf(s.output));document.getElementById('detailIds').innerHTML=kv('Span ID',s.spanId)+kv('Parent',s.parentSpanId||'root')+kv('Started',s.startedAt)+kv('Ended',s.endedAt)+kv('Thread',s.threadName||'-')+(s.errorMessage?kv('Error',s.errorMessage):'');document.getElementById('requestJson').textContent=pretty(s.input);document.getElementById('responseJson').textContent=pretty(s.output);document.getElementById('eventsJson').textContent=pretty({attributes:s.attributes||{},events:s.events||[]});const d=document.getElementById('detailDrawer');d.classList.add('open');d.setAttribute('aria-hidden','false');}"
                + "function formatMs(v){v=Number(v||0);return v<1000?v+' ms':(v/1000).toFixed(2)+' s'}function sizeOf(v){if(v==null)return '0 B';const n=new TextEncoder().encode(JSON.stringify(v)).length;return n<1024?n+' B':(n/1024).toFixed(1)+' KB'}"
                + "function showTab(id,b){document.querySelectorAll('.detail-tabs button').forEach(x=>x.classList.remove('active'));document.querySelectorAll('.tab-panel').forEach(x=>x.classList.remove('active'));b.classList.add('active');document.getElementById('tab-'+id).classList.add('active')}"
                + "function copyText(v){navigator.clipboard&&navigator.clipboard.writeText(v)}function copyPanel(id){copyText(document.getElementById(id).textContent)}function closeDrawer(){const d=document.getElementById('detailDrawer');d.classList.remove('open');d.setAttribute('aria-hidden','true');document.querySelectorAll('[data-span-index]').forEach(x=>x.classList.remove('selected'))}function collapseTree(){document.querySelectorAll('.span-row:not(.root-row)').forEach(x=>x.classList.toggle('compact'))}function switchView(id,b){document.querySelectorAll('.trace-view').forEach(x=>x.classList.remove('active'));document.querySelectorAll('.view-toggle button').forEach(x=>x.classList.remove('active'));document.getElementById(id+'View').classList.add('active');b.classList.add('active');document.querySelector('.collapse-button').hidden=id==='table'}function activateRow(e,i,row){if(e.key==='Enter'||e.key===' '){e.preventDefault();selectSpan(i,row)}}document.addEventListener('keydown',e=>{if(e.key==='Escape')closeDrawer()});"
                + (running ? "setTimeout(()=>location.reload(),2000);" : "");
    }

    private static String css() {
        return ":root{color-scheme:dark;--bg:#08111d;--panel:#0e1928;--panel2:#111f31;--line:#24354a;--text:#ecf3fb;--muted:#8fa1b6;--green:#45d6a2;--blue:#4f91ff;--cyan:#44c7cb;--purple:#a678ff;--amber:#f9b84a;--red:#ff6f72}*{box-sizing:border-box}body{margin:0;background:linear-gradient(145deg,#07101b,#0b1624 58%,#07111d);color:var(--text);font:14px/1.45 Inter,ui-sans-serif,system-ui,-apple-system,sans-serif;min-height:100vh}button,input,select{font:inherit}.topbar{height:64px;display:flex;align-items:center;gap:12px;padding:0 22px;border-bottom:1px solid var(--line);background:#091421dd;position:sticky;top:0;z-index:5;backdrop-filter:blur(14px)}.topbar>i{color:#50647b}.topbar>code{color:#b5c4d5}.back{display:grid;place-items:center;width:34px;height:34px;border:1px solid var(--line);border-radius:8px;color:var(--text);text-decoration:none}.top-actions{margin-left:auto;display:flex;gap:8px;align-items:center}.button{border:1px solid var(--line);background:#142236;color:var(--text);padding:8px 11px;border-radius:8px;text-decoration:none;cursor:pointer;white-space:nowrap}.button:hover:not(:disabled){border-color:#4f6784;background:#192b42}.button:disabled{opacity:.42;cursor:not-allowed}.button.danger{border-color:#6d3840;color:#ffb3b5}main{max-width:1800px;margin:auto;padding:20px}.run-summary,.timeline-card,.detail-card,.runs-card{background:linear-gradient(180deg,#101d2d,#0d1927);border:1px solid var(--line);border-radius:12px;box-shadow:0 16px 45px #02081038}.summary-title{display:flex;justify-content:space-between;gap:20px;padding:20px 22px;border-bottom:1px solid var(--line)}h1{font-size:24px;line-height:1.2;margin:0 0 6px}h2{font-size:17px;margin:0}h3{font-size:12px;text-transform:uppercase;letter-spacing:.08em;color:#aebed0}.summary-title p,.card-header p,.index-title p{margin:0;color:var(--muted)}.status{display:inline-flex;align-items:center;gap:7px;height:max-content;border:1px solid var(--line);border-radius:999px;padding:5px 10px;font-size:12px;font-weight:700;text-transform:capitalize}.status.completed{color:var(--green);background:#15392f}.status.running{color:#8db7ff;background:#162f54}.status.failed{color:var(--red);background:#452329}.status.input-required{color:var(--amber);background:#44341c}.pulse{width:7px;height:7px;border-radius:50%;background:currentColor;animation:pulse 1.3s infinite}@keyframes pulse{70%{box-shadow:0 0 0 8px transparent}}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(160px,1fr));padding:18px 22px;gap:18px}.summary-item{min-width:0}.summary-item span{display:block;color:var(--muted);font-size:11px;text-transform:uppercase;letter-spacing:.07em;margin-bottom:4px}.summary-item strong{display:block;overflow:hidden;text-overflow:ellipsis}.section-tabs{display:flex;gap:28px;margin:16px 0 12px;border-bottom:1px solid var(--line)}.section-tabs button{color:var(--muted);background:none;border:0;padding:10px 6px;border-bottom:2px solid transparent}.section-tabs .active{color:var(--text);border-color:var(--blue)}.trace-layout{display:grid;grid-template-columns:minmax(660px,1.1fr) minmax(420px,.9fr);gap:14px;align-items:start}.timeline-card,.detail-card{min-width:0;overflow:hidden}.card-header,.detail-header{display:flex;align-items:center;justify-content:space-between;padding:16px 18px;border-bottom:1px solid var(--line)}.scale{display:grid;grid-template-columns:minmax(260px,38%) 1fr 76px;gap:10px;padding:9px 14px;color:var(--muted);font-size:10px;border-bottom:1px solid var(--line)}.scale div{display:flex;justify-content:space-between}.scale i{font-style:normal}.span-row{display:grid;grid-template-columns:minmax(260px,38%) 1fr 76px;gap:10px;align-items:center;width:100%;min-height:58px;padding:7px 14px;color:var(--text);background:transparent;border:0;border-bottom:1px solid #1b2a3c;text-align:left;cursor:pointer}.span-row:hover,.span-row.selected{background:#17263a}.span-row.selected{box-shadow:inset 3px 0 var(--blue)}.span-row.compact{display:none}.span-label{display:flex;align-items:center;gap:10px;padding-left:calc(var(--depth,0)*18px);min-width:0;position:relative}.span-label span{min-width:0}.span-label b,.span-label small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.span-label small{color:var(--muted);font-size:11px;margin-top:2px}.state-dot{width:12px;height:12px;border-radius:50%;background:#6a7e94;box-shadow:0 0 0 4px #26374b;flex:0 0 auto}.state-dot.completed{background:var(--green)}.state-dot.running{background:var(--blue)}.state-dot.failed{background:var(--red)}.state-dot.input-required{background:var(--amber)}.track{height:28px;position:relative;background:repeating-linear-gradient(90deg,transparent 0,transparent calc(25% - 1px),#1d3046 25%)}.bar{position:absolute;top:7px;height:14px;min-width:5px;border-radius:4px;background:linear-gradient(90deg,var(--blue),#72a8ff);box-shadow:0 0 12px #4f91ff33}.bar.type-mcp-tool{background:linear-gradient(90deg,var(--amber),#ffd278)}.bar.type-llm{background:linear-gradient(90deg,var(--purple),#c4a6ff)}.bar.type-skill{background:linear-gradient(90deg,var(--green),#79e5bd)}.bar.type-iteration{background:linear-gradient(90deg,var(--cyan),#78e0e2)}.bar.type-compaction{background:linear-gradient(90deg,#7890a8,#a2b4c7)}.root-bar{background:linear-gradient(90deg,#367ded,#72a8ff)}.row-duration{text-align:right;font-variant-numeric:tabular-nums;font-size:12px}.detail-card{position:sticky;top:78px}.detail-header span:first-child{color:var(--blue);font-size:10px;letter-spacing:.1em;font-weight:800}.detail-tabs{display:flex;gap:4px;padding:0 14px;border-bottom:1px solid var(--line);overflow:auto}.detail-tabs button{background:none;border:0;color:var(--muted);padding:12px 10px;border-bottom:2px solid transparent}.detail-tabs button.active{color:var(--text);border-color:var(--blue)}.tab-panel{display:none;padding:16px;max-height:660px;overflow:auto}.tab-panel.active{display:block}.detail-metrics{display:grid;grid-template-columns:repeat(2,1fr);gap:8px}.detail-metrics div{padding:11px;background:#0a1522;border:1px solid #1c2d40;border-radius:8px}.detail-metrics span{display:block;color:var(--muted);font-size:10px;text-transform:uppercase}.detail-metrics strong{display:block;margin-top:3px}.kv{display:grid;grid-template-columns:100px 1fr;gap:9px 14px;align-items:start}.kv span{color:var(--muted)}.kv code{overflow-wrap:anywhere;color:#d8e7f7}pre{white-space:pre-wrap;word-break:break-word;background:#091522;border:1px solid #1d3046;border-radius:9px;padding:14px;color:#bfe1ff;margin:0;min-height:180px}.code-head{display:flex;justify-content:space-between;align-items:center}.code-head button{background:none;color:var(--blue);border:0;cursor:pointer}.error-banner,.warning-banner{border:1px solid #713941;background:#331e25;border-radius:10px;padding:12px 16px;margin-top:12px}.warning-banner{border-color:#705c2e;background:#30281b}.alert-title{font-weight:700}.alert-text{color:#d9e3ef;margin-top:5px}.index-main{max-width:1500px;padding:30px}.index-title{display:flex;justify-content:space-between;align-items:start;border-bottom:1px solid var(--line);padding-bottom:22px}.index-title h1{font-size:34px}.filters{display:flex;gap:12px;margin:20px 0}.filters input,.filters select{background:#0e1928;border:1px solid var(--line);border-radius:9px;color:var(--text);padding:12px 14px}.filters input{flex:1}.filters select{min-width:180px}.run-head,.run-row{display:grid;grid-template-columns:minmax(280px,1fr) 150px 110px 210px 20px;gap:16px;align-items:center;padding:13px 18px}.run-head{color:var(--muted);font-size:11px;text-transform:uppercase;border-bottom:1px solid var(--line)}.run-row{color:var(--text);text-decoration:none;border-bottom:1px solid #1b2a3c}.run-row:last-child{border:0}.run-row:hover{background:#152438}.run-row strong,.run-row small{display:block}.run-row small{color:var(--muted)}.run-row code{color:#b8d6f5}.run-row time{color:#c4d0dd}.run-row>i{font-style:normal;font-size:22px;color:var(--muted)}.empty{text-align:center;color:var(--muted);padding:70px}.back:focus,.button:focus,.span-row:focus,input:focus,select:focus{outline:2px solid var(--blue);outline-offset:1px}@media(max-width:1100px){.trace-layout{grid-template-columns:1fr}.detail-card{position:static}.summary-grid{grid-template-columns:repeat(2,1fr)}.top-actions .button:disabled{display:none}.run-head,.run-row{grid-template-columns:1fr 120px 90px}.run-head>*:nth-child(4),.run-row>*:nth-child(4){display:none}}@media(max-width:700px){main{padding:10px}.topbar{padding:0 10px}.topbar>span,.topbar>i,.topbar>code{display:none}.top-actions{overflow:auto}.summary-grid{grid-template-columns:1fr 1fr}.trace-layout{display:block}.detail-card{margin-top:12px}.scale,.span-row{grid-template-columns:minmax(170px,55%) 1fr 58px;padding-left:8px;padding-right:8px}.span-label{padding-left:calc(var(--depth,0)*8px)}.run-head{display:none}.run-row{grid-template-columns:1fr auto}.run-row>*:nth-child(n+4){display:none}.filters{flex-direction:column}}";
    }

    /** ShadCN-compatible semantic tokens with the softer shape and restrained accent from DESIGN.MD. */
    private static String themeCss() {
        return ":root{color-scheme:light;--background:#fff;--foreground:#222;--card:#fff;--card-foreground:#222;"
                + "--popover:#fff;--popover-foreground:#222;--primary:#ff385c;--primary-foreground:#fff;"
                + "--secondary:#f7f7f7;--secondary-foreground:#222;--muted-surface:#f7f7f7;--muted:#6a6a6a;"
                + "--accent:#f2f2f2;--accent-foreground:#222;--destructive:#c13515;--destructive-foreground:#fff;"
                + "--border:#ddd;--border-soft:#ebebeb;--input:#ddd;--ring:#ff385c;--code:#f7f7f7;"
                + "--success:#087f5b;--success-bg:#e9f8f2;--running:#2563eb;--running-bg:#edf4ff;"
                + "--warning:#9a6700;--warning-bg:#fff8df;--failure:#c13515;--failure-bg:#fff0ed;"
                + "--timeline-grid:#ebebeb;--selected:#fff2f4;--bar:#ff385c;--bar-soft:#ff8ca0;"
                + "--shadow:rgba(0,0,0,.02) 0 0 0 1px,rgba(0,0,0,.04) 0 2px 6px,rgba(0,0,0,.10) 0 4px 8px}"
                + ".dark{color-scheme:dark;--background:#111214;--foreground:#f5f5f5;--card:#18191c;--card-foreground:#f5f5f5;"
                + "--popover:#18191c;--popover-foreground:#f5f5f5;--primary:#ff5a76;--primary-foreground:#fff;"
                + "--secondary:#222429;--secondary-foreground:#f5f5f5;--muted-surface:#202226;--muted:#aaa;"
                + "--accent:#292b30;--accent-foreground:#fff;--destructive:#ff7763;--destructive-foreground:#1a0d0a;"
                + "--border:#34363c;--border-soft:#292b30;--input:#3b3d44;--ring:#ff5a76;--code:#121316;"
                + "--success:#52d6a4;--success-bg:#17372d;--running:#83adff;--running-bg:#1a3158;"
                + "--warning:#f3bf61;--warning-bg:#3e321d;--failure:#ff8b7b;--failure-bg:#46251f;"
                + "--timeline-grid:#303238;--selected:#352126;--bar:#ff5a76;--bar-soft:#ff9aad;"
                + "--shadow:rgba(0,0,0,.25) 0 0 0 1px,rgba(0,0,0,.2) 0 4px 12px}"
                + "*{scrollbar-color:var(--border) transparent}body{background:var(--background);color:var(--foreground);font:14px/1.45 Inter,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
                + ".lucide{width:16px;height:16px;stroke:currentColor;stroke-width:2;fill:none;stroke-linecap:round;stroke-linejoin:round;flex:0 0 auto}"
                + ".topbar{background:color-mix(in srgb,var(--background) 92%,transparent);border-color:var(--border);height:68px}"
                + ".topbar>i,.topbar>code{color:var(--muted)}.icon-button{display:inline-grid;place-items:center;width:40px;height:40px;border:1px solid var(--border);border-radius:999px;color:var(--foreground);background:var(--secondary);text-decoration:none;cursor:pointer}"
                + ".icon-button:hover{background:var(--accent)}.theme-toggle .sun{display:none}.dark .theme-toggle .sun{display:block}.dark .theme-toggle .moon{display:none}"
                + ".button{display:inline-flex;align-items:center;justify-content:center;gap:7px;background:var(--card);color:var(--foreground);border-color:var(--border);border-radius:8px;font-weight:500;min-height:38px}"
                + ".button:hover:not(:disabled){background:var(--accent);border-color:var(--border)}.button.danger{color:var(--destructive);border-color:color-mix(in srgb,var(--destructive) 35%,var(--border))}"
                + "main{padding:24px}.run-summary,.timeline-card,.detail-card,.runs-card{background:var(--card);color:var(--card-foreground);border-color:var(--border);border-radius:14px;box-shadow:none}"
                + ".run-summary:hover,.timeline-card:hover,.detail-card:hover,.runs-card:hover{box-shadow:var(--shadow)}"
                + ".summary-title,.card-header,.detail-header,.section-tabs,.detail-tabs,.scale,.run-head{border-color:var(--border)}"
                + "h1{font-size:28px;font-weight:650;letter-spacing:-.02em}h2{font-size:17px;font-weight:600}h3{color:var(--muted);font-weight:600}"
                + ".summary-title p,.card-header p,.index-title p,.summary-item span,.span-label small,.kv span,.run-row small{color:var(--muted)}"
                + ".status{border:0;background:var(--secondary);font-size:11px;letter-spacing:.01em;padding:5px 9px}.status .lucide,.status-mark .lucide{width:13px;height:13px}"
                + ".status.completed{color:var(--success);background:var(--success-bg)}.status.running{color:var(--running);background:var(--running-bg)}.status.failed{color:var(--failure);background:var(--failure-bg)}.status.input-required{color:var(--warning);background:var(--warning-bg)}"
                + ".section-tabs .active,.detail-tabs button.active{border-color:var(--primary);color:var(--foreground)}.section-tabs button,.detail-tabs button{color:var(--muted)}"
                + ".trace-layout{grid-template-columns:minmax(720px,1.35fr) minmax(390px,.65fr);gap:16px}.card-header{gap:18px}.view-actions,.view-toggle{display:flex;align-items:center;gap:8px}.view-toggle{background:var(--secondary);border-radius:10px;padding:3px}"
                + ".loop-diagram{min-height:190px;padding:20px;border-bottom:1px solid var(--border);overflow:auto;background:color-mix(in srgb,var(--card) 96%,var(--secondary))}.loop-diagram svg{display:block;min-width:680px;max-width:none;height:auto;margin:auto}.diagram-unavailable{display:grid;place-items:center;min-height:150px;color:var(--muted)}"
                + ".view-toggle button{display:inline-flex;align-items:center;gap:6px;border:0;background:transparent;color:var(--muted);border-radius:8px;padding:7px 10px;cursor:pointer;white-space:nowrap}.view-toggle button.active{background:var(--card);color:var(--foreground);box-shadow:0 1px 3px rgba(0,0,0,.10)}"
                + ".trace-view{display:none}.trace-view.active{display:block}.scale{color:var(--muted)}.span-row{color:var(--foreground);border-color:var(--border-soft)}.span-row:hover,.span-row.selected{background:var(--selected)}.span-row.selected{box-shadow:inset 3px 0 var(--primary)}"
                + ".status-mark{display:inline-grid;place-items:center;color:var(--muted);flex:0 0 auto}.status-mark.completed{color:var(--success)}.status-mark.running{color:var(--running)}.status-mark.failed{color:var(--failure)}.status-mark.input-required{color:var(--warning)}"
                + ".track{background:repeating-linear-gradient(90deg,transparent 0,transparent calc(25% - 1px),var(--timeline-grid) 25%)}.bar,.root-bar{background:linear-gradient(90deg,var(--bar),var(--bar-soft));box-shadow:none}.bar.type-mcp-tool{background:linear-gradient(90deg,#d97706,#fbbf24)}.bar.type-llm{background:linear-gradient(90deg,#7c3aed,#a78bfa)}.bar.type-skill{background:linear-gradient(90deg,#059669,#34d399)}.bar.type-iteration{background:linear-gradient(90deg,#0284c7,#38bdf8)}.bar.type-compaction{background:linear-gradient(90deg,#64748b,#94a3b8)}.bar.type-object-store{background:linear-gradient(90deg,#2563eb,#60a5fa)}"
                + ".span-table-wrap{overflow:auto;max-height:660px}.span-table{width:100%;min-width:960px;table-layout:fixed;border-collapse:separate;border-spacing:0;font-size:12px}.span-table th{position:sticky;top:0;z-index:1;background:var(--secondary);color:var(--muted);text-align:left;text-transform:uppercase;letter-spacing:.055em;font-size:10px;font-weight:700;padding:11px 12px;border-bottom:1px solid var(--border)}"
                + ".span-table th:nth-child(1){width:112px}.span-table th:nth-child(2){width:100px}.span-table th:nth-child(3){width:130px}.span-table th:nth-child(4),.span-table th:nth-child(5){width:150px}.span-table th:nth-child(6){width:55px}.span-table th:nth-child(7){width:135px}.span-table th:nth-child(8){width:75px}"
                + ".span-table td{max-width:210px;padding:11px 12px;border-bottom:1px solid var(--border-soft);vertical-align:middle}.span-table tbody tr{cursor:pointer;outline:0}.span-table tbody tr:hover,.span-table tbody tr.selected{background:var(--selected)}.span-table tbody tr.selected{box-shadow:inset 3px 0 var(--primary)}"
                + ".span-table .kind{display:inline-flex;align-items:center;gap:6px;color:var(--muted);text-transform:lowercase}.span-table .kind .lucide{width:14px;height:14px}.cell-preview,.cell-error{display:block;max-width:210px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--muted)}.cell-error.has-error{color:var(--failure)}.latency{font-variant-numeric:tabular-nums;white-space:nowrap}"
                + ".detail-card{top:82px}.detail-header span:first-child,.code-head button{color:var(--primary)}.detail-metrics div,pre{background:var(--code);border-color:var(--border);color:var(--foreground)}.kv code,.run-row code,.run-row time{color:var(--foreground)}"
                + ".error-banner{border-color:color-mix(in srgb,var(--failure) 38%,var(--border));background:var(--failure-bg)}.warning-banner{border-color:color-mix(in srgb,var(--warning) 38%,var(--border));background:var(--warning-bg)}.alert-text{color:var(--foreground)}"
                + ".index-main{max-width:1500px}.index-title{border-color:var(--border)}.index-title h1{font-size:28px}.filters input,.filters select{background:var(--card);border-color:var(--input);color:var(--foreground);height:46px;border-radius:999px}.search-field{position:relative;display:flex;align-items:center;flex:1}.search-field .lucide{position:absolute;left:15px;color:var(--muted);z-index:1}.filters .search-field input{width:100%;padding-left:42px}.run-row{color:var(--foreground);border-color:var(--border-soft)}.run-row:hover{background:var(--secondary)}.run-row>.lucide{color:var(--muted)}"
                + ".icon-button:focus,.button:focus,.span-row:focus,.span-table tr:focus,input:focus,select:focus{outline:2px solid var(--ring);outline-offset:2px}"
                + ".brand{display:inline-flex;align-items:center;gap:10px;color:var(--foreground);text-decoration:none;white-space:nowrap}.brand img{width:30px;height:30px;object-fit:contain}.brand strong{font-size:16px;letter-spacing:-.01em}.breadcrumb-link{color:var(--muted);text-decoration:none}.breadcrumb-link:hover{color:var(--foreground)}.topbar>code{max-width:420px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.topbar>.lucide{color:var(--muted)}"
                + ".trace-layout{display:block;margin-top:18px}.timeline-card{width:100%}.detail-drawer{display:flex;flex-direction:column;position:fixed;z-index:30;top:68px;right:0;bottom:0;width:min(560px,42vw);border-radius:0;border-width:0 0 0 1px;transform:translateX(102%);transition:transform .22s ease;box-shadow:-18px 0 45px rgba(0,0,0,.16);overflow:hidden}.detail-drawer.open{transform:translateX(0)}.drawer-actions{display:flex;align-items:center;gap:10px}.drawer-close{width:34px;height:34px}.detail-drawer .tab-panel{max-height:none;min-height:0}.detail-drawer .tab-panel.active{flex:1;overflow:auto}.section-tabs{display:none}"
                + "@media(max-width:1250px){.trace-layout{display:block}.detail-card{position:fixed}.span-table-wrap{max-height:none}.detail-drawer{width:min(560px,52vw)}}"
                + "@media(max-width:760px){main{padding:12px}.brand strong,.breadcrumb-link,.topbar>i,.topbar>code,.topbar>.lucide{display:none}.topbar{padding:0 12px}.top-actions{gap:6px}.icon-button{width:36px;height:36px}.card-header{align-items:flex-start;flex-direction:column}.view-actions{width:100%;justify-content:space-between}.collapse-button{display:none}.view-toggle{width:100%}.view-toggle button{flex:1;justify-content:center}.theme-toggle{display:grid}.summary-grid{grid-template-columns:1fr 1fr}.filters{flex-direction:column}.search-field{width:100%}.detail-drawer{top:68px;width:100%;margin:0}.detail-header{gap:10px}.detail-tabs{padding:0 6px}}";
    }

    private static String themeBootstrap() {
        return "<script>(function(){try{var t=localStorage.getItem('agent-trace-theme');var d=t?t==='dark':matchMedia('(prefers-color-scheme:dark)').matches;if(d)document.documentElement.classList.add('dark')}catch(e){}})()</script>";
    }

    private static String themeScript() {
        return "function toggleTheme(){const d=document.documentElement.classList.toggle('dark');try{localStorage.setItem('agent-trace-theme',d?'dark':'light')}catch(e){}}";
    }

    private static String themeToggle() {
        return "<button class=\"icon-button theme-toggle\" onclick=\"toggleTheme()\" title=\"Toggle light and dark mode\" aria-label=\"Toggle light and dark mode\">"
                + "<span class=\"moon\">" + icon("moon") + "</span><span class=\"sun\">" + icon("sun") + "</span></button>";
    }

    private static String statusIcon(String status) {
        String css = statusClass(status);
        String iconName = "completed".equals(css) ? "circle-check" : "failed".equals(css) ? "circle-x"
                : "input-required".equals(css) ? "circle-alert" : "running".equals(css) ? "loader-circle" : "circle";
        return "<span class=\"status-mark " + css + "\">" + icon(iconName) + "</span>";
    }

    private static String iconForType(String type) {
        String normalized = typeClass(type);
        if ("llm".equals(normalized)) return "brain-circuit";
        if ("mcp-tool".equals(normalized)) return "wrench";
        if ("skill".equals(normalized)) return "blocks";
        if ("iteration".equals(normalized)) return "repeat-2";
        if ("compaction".equals(normalized)) return "minimize-2";
        if ("object-store".equals(normalized)) return "database";
        return "activity";
    }

    /** Inline Lucide icons keep the report dependency-free and usable without internet access. */
    private static String icon(String name) {
        String paths;
        switch (name) {
            case "arrow-left": paths = "<path d=\"m12 19-7-7 7-7\"/><path d=\"M19 12H5\"/>"; break;
            case "download": paths = "<path d=\"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4\"/><path d=\"m7 10 5 5 5-5\"/><path d=\"M12 15V3\"/>"; break;
            case "refresh-cw": paths = "<path d=\"M21 12a9 9 0 0 1-15.2 6.5L3 16\"/><path d=\"M3 21v-5h5\"/><path d=\"M3 12A9 9 0 0 1 18.2 5.5L21 8\"/><path d=\"M21 3v5h-5\"/>"; break;
            case "copy": paths = "<rect width=\"14\" height=\"14\" x=\"8\" y=\"8\" rx=\"2\"/><path d=\"M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2\"/>"; break;
            case "x": paths = "<path d=\"M18 6 6 18M6 6l12 12\"/>"; break;
            case "moon": paths = "<path d=\"M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z\"/>"; break;
            case "sun": paths = "<circle cx=\"12\" cy=\"12\" r=\"4\"/><path d=\"M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.66 17.66l1.41 1.41M2 12h2M20 12h2M6.34 17.66l-1.41 1.41M19.07 4.93l-1.41 1.41\"/>"; break;
            case "search": paths = "<circle cx=\"11\" cy=\"11\" r=\"8\"/><path d=\"m21 21-4.3-4.3\"/>"; break;
            case "chevron-right": paths = "<path d=\"m9 18 6-6-6-6\"/>"; break;
            case "circle-check": paths = "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"m9 12 2 2 4-4\"/>"; break;
            case "circle-x": paths = "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"m15 9-6 6M9 9l6 6\"/>"; break;
            case "circle-alert": paths = "<circle cx=\"12\" cy=\"12\" r=\"10\"/><path d=\"M12 8v4M12 16h.01\"/>"; break;
            case "loader-circle": paths = "<path d=\"M21 12a9 9 0 1 1-6.2-8.6\"/>"; break;
            case "circle": paths = "<circle cx=\"12\" cy=\"12\" r=\"10\"/>"; break;
            case "gantt-chart": paths = "<path d=\"M8 6h10M6 12h9M11 18h7\"/><path d=\"M3 6h.01M3 12h.01M3 18h.01\"/>"; break;
            case "table-2": paths = "<path d=\"M9 3H5a2 2 0 0 0-2 2v4m6-6h10a2 2 0 0 1 2 2v4M9 3v18m0-12h12M9 15h12M3 9h6M3 15h6M3 21h16a2 2 0 0 0 2-2V9\"/>"; break;
            case "list-tree": paths = "<path d=\"M21 12h-8M21 6H8M21 18h-8M3 6h.01M3 12h.01M3 18h.01M8 6v6h5\"/>"; break;
            case "brain-circuit": paths = "<path d=\"M9.5 4A2.5 2.5 0 0 0 7 6.5v11A2.5 2.5 0 0 0 9.5 20c.9 0 1.7-.5 2.1-1.2.4.7 1.2 1.2 2.1 1.2a2.5 2.5 0 0 0 2.5-2.5v-11A2.5 2.5 0 0 0 13.7 4c-.9 0-1.7.5-2.1 1.2A2.5 2.5 0 0 0 9.5 4Z\"/><path d=\"M8 9h3M13 15h3M11 9v6M11 12h4\"/>"; break;
            case "wrench": paths = "<path d=\"M14.7 6.3a4 4 0 0 0-5-5l2.1 2.1-2.8 2.8-2.1-2.1a4 4 0 0 0 5 5L5 16l3 3 6.9-6.9a4 4 0 0 0 5-5l-2.1 2.1-2.8-2.8 2.1-2.1Z\"/>"; break;
            case "blocks": paths = "<rect width=\"7\" height=\"7\" x=\"3\" y=\"3\" rx=\"1\"/><rect width=\"7\" height=\"7\" x=\"14\" y=\"3\" rx=\"1\"/><rect width=\"7\" height=\"7\" x=\"3\" y=\"14\" rx=\"1\"/><path d=\"M17.5 14v7M14 17.5h7\"/>"; break;
            case "repeat-2": paths = "<path d=\"m2 9 3-3 3 3\"/><path d=\"M5 6h11a4 4 0 0 1 4 4v1M22 15l-3 3-3-3\"/><path d=\"M19 18H8a4 4 0 0 1-4-4v-1\"/>"; break;
            case "minimize-2": paths = "<path d=\"m14 10 7-7M20 10h-6V4M3 21l7-7M4 14h6v6\"/>"; break;
            case "database": paths = "<ellipse cx=\"12\" cy=\"5\" rx=\"9\" ry=\"3\"/><path d=\"M3 5v14c0 1.7 4 3 9 3s9-1.3 9-3V5\"/><path d=\"M3 12c0 1.7 4 3 9 3s9-1.3 9-3\"/>"; break;
            default: paths = "<path d=\"M22 12h-4l-3 9L9 3l-3 9H2\"/>";
        }
        return "<svg class=\"lucide lucide-" + escapeAttribute(name) + "\" viewBox=\"0 0 24 24\" aria-hidden=\"true\">" + paths + "</svg>";
    }

    private static String compact(Object value, int maxLength) {
        if (value == null) return "-";
        String rendered = TraceRedactor.toRedactedJson(value).replaceAll("\\s+", " ").trim();
        return rendered.length() <= maxLength ? rendered : rendered.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String nullSafeCell(String value) { return value == null || value.isEmpty() ? "-" : value; }

    private static String summaryItem(String label, String value) {
        return "<div class=\"summary-item\"><span>" + escape(label) + "</span><strong title=\""
                + escapeAttribute(value) + "\">" + escape(value) + "</strong></div>";
    }

    private static String alert(String value, String cssClass, String title) {
        if (value == null || value.isEmpty()) return "";
        return "<section class=\"" + cssClass + "\"><div class=\"alert-title\">" + escape(title)
                + "</div><div class=\"alert-text\">" + escape(value) + "</div></section>";
    }

    private static String button(String label, boolean danger, String title) {
        return "<button class=\"button" + (danger ? " danger" : "") + "\" disabled title=\""
                + escapeAttribute(title) + "\">" + escape(label) + "</button>";
    }

    private static Map<String, Integer> calculateDepths(List<TraceSpan> spans) {
        Map<String, TraceSpan> byId = new HashMap<>();
        for (TraceSpan span : spans) byId.put(span.getSpanId(), span);
        Map<String, Integer> result = new HashMap<>();
        for (TraceSpan span : spans) {
            int depth = 0;
            String parent = span.getParentSpanId();
            int guard = 0;
            while (parent != null && byId.containsKey(parent) && guard++ < 32) {
                depth++;
                parent = byId.get(parent).getParentSpanId();
            }
            result.put(span.getSpanId(), depth);
        }
        return result;
    }

    private static double percent(long value, long total) {
        return total <= 0 ? 0 : Math.max(0, Math.min(100, (value * 100.0) / total));
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String formatDuration(long millis) {
        if (millis < 1000) return millis + " ms";
        if (millis < 60_000) return String.format(Locale.ROOT, "%.2f s", millis / 1000.0);
        return String.format(Locale.ROOT, "%d:%02d", millis / 60_000, (millis / 1000) % 60);
    }

    private static String displayTime(long epochMs) {
        return epochMs > 0 ? DISPLAY_TIME.format(Instant.ofEpochMilli(epochMs)) : "n/a";
    }

    private static String traceIdentityLabel(ExecutionTrace trace) {
        return hasDistinctCorrelationId(trace) ? "Correlation ID" : "Trace ID";
    }

    private static String traceIdentityValue(ExecutionTrace trace) {
        return hasDistinctCorrelationId(trace) ? trace.getConversationId() : trace.getExecutionId();
    }

    private static boolean hasDistinctCorrelationId(ExecutionTrace trace) {
        String correlationId = trace.getConversationId();
        return correlationId != null && !correlationId.trim().isEmpty()
                && !correlationId.equals(trace.getExecutionId());
    }

    private static String displaySpanName(TraceSpan span) {
        String name = span.getName();
        if (name != null && "ITERATION".equals(span.getType())
                && name.toLowerCase(Locale.ROOT).startsWith("react iteration ")) {
            return name.substring("ReAct ".length());
        }
        return name;
    }

    private static String statusClass(String status) {
        return status == null ? "unknown" : status.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String typeClass(String type) {
        return type == null ? "unknown" : type.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String shortId(String id) {
        return id == null || id.length() <= 18 ? nullSafe(id) : id.substring(0, 18) + "...";
    }

    private static String safeScriptJson(Object value) {
        return TraceRedactor.toRedactedJson(value)
                .replace("&", "\\u0026")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    private static String nullSafe(String value) { return value == null ? "n/a" : value; }

    private static String brandLogoPath(String reportPath) {
        String path = reportPath == null || reportPath.isBlank() ? "/traces" : reportPath;
        int traces = path.lastIndexOf("/traces");
        String base = traces >= 0 ? path.substring(0, traces) : "";
        return base + "/assets/agent-composer.svg";
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeAttribute(String value) { return escape(value); }

    private static String notFound() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Trace not found</title><style>"
                + css() + themeCss() + "</style>" + themeBootstrap() + "</head><body><main><section class=\"empty\"><h1>Trace not found</h1>"
                + "<p>The execution ID does not exist or is no longer retained.</p></section></main></body></html>";
    }
}
