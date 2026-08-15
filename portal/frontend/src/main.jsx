import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';

const metricFields = [
  ['deployment_frequency', 'Deployment frequency', '#577363'],
  ['lead_time_hours', 'Lead time (h)', '#b3914b'],
  ['change_failure_rate', 'Change failure rate', '#b85c4a'],
  ['pr_review_time_delta_hours', 'Review delta (h)', '#566b9c'],
  ['rework_rate', 'Rework rate', '#856a99'],
  ['review_queue_health', 'Queue health', '#4b8a7a'],
  ['spec_alignment_score', 'Spec alignment', '#8da84e']
];

function num(value) { const parsed = Number(value); return Number.isFinite(parsed) ? parsed : null; }
function props(node) { try { return JSON.parse(node.dataset.reactProps || '[]'); } catch { return []; } }

function QualityAnalytics({ rows }) {
  const chartRef = useRef(null);
  const chartInstance = useRef(null);
  const [field, setField] = useState('spec_alignment_score');
  const active = metricFields.find(([key]) => key === field) || metricFields[0];
  useEffect(() => {
    if (!chartRef.current || !window.echarts || !rows.length) return undefined;
    chartInstance.current?.dispose();
    const chart = window.echarts.init(chartRef.current, null, { renderer: 'svg' });
    chartInstance.current = chart;
    const ordered = [...rows].reverse();
    chart.setOption({
      animationDuration: 180,
      tooltip: { trigger: 'axis' },
      grid: { top: 26, left: 42, right: 18, bottom: 42 },
      xAxis: { type: 'category', data: ordered.map(row => String(row.period_end || 'period').slice(0, 10)) },
      yAxis: { type: 'value' },
      series: [{ name: active[1], type: 'line', smooth: true, showSymbol: false, data: ordered.map(row => num(row[active[0]])), lineStyle: { color: active[2], width: 2 }, itemStyle: { color: active[2] } }]
    });
    const resize = () => chart.resize();
    window.addEventListener('resize', resize);
    return () => { window.removeEventListener('resize', resize); chart.dispose(); };
  }, [rows, active]);
  if (!rows.length) return <div className="react-workspace"><strong>No quality periods are available yet.</strong><span>Only persisted metric snapshots are charted; the server-rendered workspace remains the source of truth.</span></div>;
  return <div className="react-workspace"><div className="react-toolbar"><label>Explore metric <select value={field} onChange={event => setField(event.target.value)}>{metricFields.map(([key, label]) => <option value={key} key={key}>{label}</option>)}</select></label><span>{rows.length} recorded periods</span></div><div ref={chartRef} className="react-chart" role="img" aria-label={`${active[1]} trend`}>{!window.echarts && 'Chart module unavailable. Review the server-rendered table below.'}</div></div>;
}

function TraceabilityExplorer({ trace }) {
  const graphRef = useRef(null);
  const [selected, setSelected] = useState(null);
  useEffect(() => {
    if (!graphRef.current || !window.cytoscape || !trace.nodes?.length) return undefined;
    const cy = window.cytoscape({ container: graphRef.current, elements: [
      ...trace.nodes.map(node => ({ data: { id: String(node.id), label: node.label || node.external_key, type: node.node_type || 'NODE', key: node.external_key || '', status: node.status || 'UNSPECIFIED' } })),
      ...(trace.edges || []).map(edge => ({ data: { id: String(edge.id), source: String(edge.source_node_id), target: String(edge.target_node_id), label: edge.relation || 'links' } }))
    ], style: [{ selector: 'node', style: { 'background-color': '#5f846f', label: 'data(label)', color: '#19342d', 'font-size': 11, 'text-valign': 'bottom', 'text-margin-y': 8, width: 30, height: 30 } }, { selector: 'edge', style: { width: 1.5, 'line-color': '#9aa99a', 'target-arrow-color': '#9aa99a', 'target-arrow-shape': 'triangle', label: 'data(label)', color: '#5d7063', 'font-size': 9, 'text-rotation': 'autorotate', 'curve-style': 'bezier' } }, { selector: ':selected', style: { 'background-color': '#c8e879' } }], layout: { name: 'breadthfirst', directed: true, padding: 28, spacingFactor: 1.2 } });
    const announce = node => { cy.elements().unselect(); node.select(); setSelected({ type: node.data('type'), label: node.data('label'), key: node.data('key'), status: node.data('status') }); };
    cy.on('tap', 'node', event => announce(event.target));
    const keyboard = event => { const nodes = cy.nodes(); if (!nodes.length || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return; event.preventDefault(); const next = (nodes.indexOf(nodes.filter(':selected')[0]) + (event.key === 'ArrowLeft' || event.key === 'ArrowUp' ? -1 : 1) + nodes.length) % nodes.length; announce(nodes[next]); };
    graphRef.current.addEventListener('keydown', keyboard);
    return () => { graphRef.current?.removeEventListener('keydown', keyboard); cy.destroy(); };
  }, [trace]);
  return <div className="react-workspace"><div ref={graphRef} className="trace-graph" tabIndex="0" aria-label="Traceability graph. Use arrow keys to select nodes." />{selected ? <div className="trace-selection" aria-live="polite"><strong>{selected.label}</strong><span>{selected.type} · {selected.key} · {selected.status}</span></div> : <div className="trace-selection" aria-live="polite">Select a node to inspect its governed delivery detail.</div>}</div>;
}

function EvidenceWorkspace({ runs }) {
  const [query, setQuery] = useState('');
  const matched = useMemo(() => runs.filter(run => `${run.status || ''} ${run.idempotencyKey || ''} ${run.modelPin || ''} ${run.kitVersion || ''}`.toLowerCase().includes(query.toLowerCase())), [runs, query]);
  const blocked = matched.filter(run => ['FAILED', 'BLOCKED'].includes(run.status)).length;
  return <div className="react-workspace evidence-workspace"><label>Filter live validation evidence <input value={query} onChange={event => setQuery(event.target.value)} placeholder="Status, model pin, kit or key" /></label><span aria-live="polite">{matched.length} of {runs.length} runs match · {blocked} require attention. Select a server-rendered run for the immutable evidence record.</span></div>;
}

function ReviewGuardrail({ reviews, exceptions, organizationId, projectId }) {
  const pending = reviews.filter(review => review.status === 'PENDING');
  const pendingExceptions = exceptions.filter(request => request.status === 'PENDING');
  const [state, setState] = useState({ busy: '', message: '' });
  async function decide(review, decision) {
    setState({ busy: review.id, message: '' });
    const csrf = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    const body = new URLSearchParams({ org: organizationId, decision, note: '' });
    try {
      const response = await fetch(`/app/projects/${projectId}/review-items/${review.id}/decision`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded', ...(csrf ? { [header]: csrf } : {}) }, body, credentials: 'same-origin', redirect: 'manual' });
      if (response.type === 'opaqueredirect' || response.status === 0 || response.ok || response.status === 302) window.location.assign(`/app/reviews?org=${organizationId}&project=${projectId}`);
      else setState({ busy: '', message: 'Decision was not accepted. The server-rendered review form below remains available.' });
    } catch (_) { setState({ busy: '', message: 'Network error. Use the server-rendered review form below.' }); }
  }
  return <div className="react-workspace review-guardrail"><strong>{pending.length} pending review decision{pending.length === 1 ? '' : 's'} · {pendingExceptions.length} pending exception{pendingExceptions.length === 1 ? '' : 's'}</strong><span>Every decision remains server-authorized, CSRF-protected and appended to the immutable audit ledger. Exception approvals also require an explicit UTC expiry.</span>{pending.slice(0, 3).map(review => <div className="react-review-row" key={review.id}><span>{review.title || review.review_type}</span><div><button type="button" disabled={state.busy === review.id} onClick={() => decide(review, 'APPROVED')}>Approve</button><button type="button" disabled={state.busy === review.id} onClick={() => decide(review, 'REJECTED')}>Reject</button></div></div>)}{state.message && <span role="alert">{state.message}</span>}</div>;
}

const registry = { quality: data => <QualityAnalytics rows={Array.isArray(data) ? data : []} />, trace: data => <TraceabilityExplorer trace={data || { nodes: [], edges: []}} />, evidence: data => <EvidenceWorkspace runs={Array.isArray(data) ? data : []} />, review: data => <ReviewGuardrail reviews={Array.isArray(data?.reviews) ? data.reviews : []} exceptions={Array.isArray(data?.exceptions) ? data.exceptions : []} organizationId={data?.organizationId || ''} projectId={data?.projectId || ''} /> };
document.querySelectorAll('[data-react-island]').forEach(node => { const factory = registry[node.dataset.reactIsland]; if (factory) createRoot(node).render(factory(props(node))); });
