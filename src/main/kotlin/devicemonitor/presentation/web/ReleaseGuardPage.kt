package devicemonitor.presentation.web

// Self-contained offline UI. Plain JS avoids Kotlin raw-string interpolation conflicts.
internal val RELEASE_GUARD_PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Release Guard · ADB Performance Studio</title>
<style>
  .guard-grid { display:grid; grid-template-columns:minmax(0,1.4fr) minmax(290px,.6fr); gap:18px; margin-top:28px; align-items:start; }
  .guard-stack { display:grid; gap:18px; }
  .guard-card { padding:22px; }
  .guard-row { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
  .guard-row.three { grid-template-columns:repeat(3,minmax(0,1fr)); }
  .guard-field { display:grid; gap:7px; min-width:0; }
  .guard-field.span { grid-column:1/-1; }
  .guard-field label { color:#c9c4bb; font-size:10px; font-weight:800; letter-spacing:.08em; text-transform:uppercase; }
  .guard-field input,.guard-field select,.guard-field textarea { width:100%; min-height:40px; padding:9px 11px; border:1px solid var(--line); border-radius:10px; color:var(--text); background:#111; outline:none; }
  .guard-field textarea { min-height:86px; resize:vertical; font:11px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace; }
  .guard-field input:focus,.guard-field select:focus,.guard-field textarea:focus { border-color:rgba(232,182,109,.55); box-shadow:0 0 0 3px rgba(232,182,109,.08); }
  .guard-hint { color:var(--quiet); font-size:10px; line-height:1.45; }
  .guard-toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:16px; }
  .guard-toolbar-actions { display:flex; gap:8px; flex-wrap:wrap; }
  .guard-list { display:grid; gap:10px; }
  .guard-item { padding:14px; border:1px solid var(--line); border-radius:14px; background:rgba(0,0,0,.2); }
  .guard-item-head { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:12px; }
  .guard-item-title { color:var(--text); font-size:12px; font-weight:800; }
  .guard-item-meta { color:var(--quiet); font:10px/1.4 ui-monospace,SFMono-Regular,Menlo,monospace; }
  .guard-item-actions { display:flex; gap:6px; }
  .guard-icon-button { min-width:31px; min-height:31px; padding:5px; border:1px solid var(--line); border-radius:9px; color:var(--muted); background:rgba(255,255,255,.03); cursor:pointer; }
  .guard-icon-button:hover { color:var(--text); border-color:var(--line-strong); }
  .guard-empty { padding:22px; border:1px dashed var(--line-strong); border-radius:14px; color:var(--quiet); text-align:center; font-size:11px; }
  .guard-divider { height:1px; margin:18px 0; background:var(--line); }
  .guard-readiness { display:grid; gap:9px; margin:16px 0; }
  .guard-check { display:flex; align-items:flex-start; gap:9px; padding:10px; border:1px solid var(--line); border-radius:11px; color:var(--muted); background:rgba(255,255,255,.02); font-size:11px; line-height:1.45; }
  .guard-check.ok { border-color:rgba(97,216,156,.22); color:#c8ffe4; }
  .guard-check.bad { border-color:rgba(255,118,118,.25); color:#ffd0d0; }
  .guard-check-dot { width:7px; height:7px; margin-top:4px; flex:none; border-radius:50%; background:currentColor; }
  .guard-capabilities { display:flex; flex-wrap:wrap; gap:6px; margin-top:10px; }
  .guard-chip { padding:5px 8px; border:1px solid var(--line); border-radius:999px; color:var(--quiet); background:rgba(255,255,255,.025); font:9px/1 ui-monospace,SFMono-Regular,Menlo,monospace; }
  .guard-chip.on { border-color:rgba(88,214,199,.25); color:#bffcf5; background:rgba(88,214,199,.07); }
  .guard-run { width:100%; min-height:48px; margin-top:4px; font-size:12px; }
  .guard-progress { display:none; margin-top:18px; }
  .guard-progress.show { display:block; }
  .guard-progress-track { height:6px; overflow:hidden; border-radius:999px; background:rgba(255,255,255,.06); }
  .guard-progress-bar { width:4%; height:100%; border-radius:inherit; background:linear-gradient(90deg,var(--violet),var(--teal)); transition:width .35s ease; }
  .guard-progress-copy { margin-top:9px; color:var(--muted); font-size:11px; }
  .guard-result { display:none; margin-top:18px; padding:16px; border:1px solid var(--line); border-radius:14px; background:rgba(0,0,0,.22); }
  .guard-result.show { display:block; }
  .guard-verdict { font-size:22px; font-weight:850; letter-spacing:-.03em; }
  .guard-verdict.pass { color:var(--green); } .guard-verdict.warning { color:var(--amber); } .guard-verdict.fail,.guard-verdict.cancelled { color:var(--red); }
  .guard-findings { display:grid; gap:7px; margin-top:12px; }
  .guard-finding { display:flex; justify-content:space-between; gap:12px; padding:9px 10px; border:1px solid var(--line); border-radius:9px; color:var(--muted); font-size:10px; }
  .guard-finding strong { color:var(--text); }
  .guard-toast { min-height:18px; margin-top:10px; color:var(--muted); font-size:11px; }
  .guard-toast.bad { color:var(--red); }
  .guard-sticky { position:sticky; top:86px; }
  .guard-artifacts { display:flex; flex-wrap:wrap; gap:7px; margin-top:12px; }
  .guard-viewer { position:fixed; inset:0; z-index:1000; display:flex; flex-direction:column; background:#090909; }
  .guard-viewer[hidden] { display:none; }
  .guard-viewer-bar { display:flex; align-items:center; justify-content:space-between; gap:12px; min-height:58px; padding:9px 16px; border-bottom:1px solid var(--line); background:#10100f; }
  .guard-viewer-frame { width:100%; flex:1; border:0; background:#fff; }
  @media(max-width:900px){.guard-grid{grid-template-columns:1fr}.guard-sticky{position:static}.guard-row.three{grid-template-columns:1fr 1fr}}
  @media(max-width:620px){.guard-row,.guard-row.three{grid-template-columns:1fr}.guard-card{padding:17px}.guard-item-head{align-items:flex-start}.guard-toolbar{align-items:flex-start;flex-direction:column}}
</style>
</head>
<body>
<div class="studio-ambient" aria-hidden="true"><span class="studio-orb gold"></span><span class="studio-orb violet"></span><span class="studio-orb teal"></span></div>
<header class="studio-appbar">
  <a class="studio-brand" href="/" style="text-decoration:none"><span class="studio-brand-mark">APS</span><span class="studio-brand-copy"><span class="studio-brand-name">ADB Performance Studio</span><span class="studio-brand-meta">Release Guard · __BUILD__</span></span></a>
  <div class="studio-appbar-context"></div>
  <nav class="studio-actions" aria-label="Application"><a class="studio-link" href="/">Home</a><a class="studio-link" href="/player-debugger">Player Debugger</a><a class="studio-link" href="/dashboard">Live dashboard</a><a class="studio-link" href="/compare">Compare</a></nav>
</header>
<main class="studio-shell">
  <section class="studio-enter">
    <p class="studio-eyebrow">Offline performance release gate</p>
    <h1 class="studio-page-title">Measure. Compare. Decide.</h1>
    <p class="studio-page-copy">Create a repeatable Android journey, enforce explicit performance budgets, and keep every result and artifact on this workstation.</p>
  </section>

  <div class="guard-grid studio-enter">
    <div class="guard-stack">
      <section class="studio-panel guard-card" aria-labelledby="projectTitle">
        <div class="guard-toolbar"><div><h2 class="studio-section-title" id="projectTitle">Project</h2><p class="studio-section-copy">Group scenarios and baselines by application package.</p></div><div class="guard-toolbar-actions"><button class="studio-button" type="button" onclick="newProject()">New</button><button class="studio-button primary" type="button" onclick="saveProject().catch(function(){})">Save project</button></div></div>
        <div class="guard-row">
          <div class="guard-field span"><label for="projectSelect">Saved project</label><select id="projectSelect" onchange="selectProject()"><option value="">Create a project</option></select></div>
          <div class="guard-field"><label for="projectName">Project name</label><input id="projectName" placeholder="TV Player"></div>
          <div class="guard-field"><label for="projectId">Project ID</label><input id="projectId" placeholder="tv-player"></div>
          <div class="guard-field span"><label for="projectPackage">Android package</label><input id="projectPackage" placeholder="com.example.player"><span class="guard-hint">Used by launch, process, PSS, jank, and application-specific budgets.</span></div>
        </div>
      </section>

      <section class="studio-panel guard-card" aria-labelledby="scenarioTitle">
        <div class="guard-toolbar"><div><h2 class="studio-section-title" id="scenarioTitle">Scenario</h2><p class="studio-section-copy">A versioned journey. Saving changed content creates a new immutable revision.</p></div><div class="guard-toolbar-actions"><button class="studio-button" type="button" onclick="newScenario()">New</button><button class="studio-button primary" type="button" onclick="saveScenario().catch(function(){})">Save scenario</button></div></div>
        <div class="guard-row three">
          <div class="guard-field span"><label for="scenarioSelect">Saved scenario</label><select id="scenarioSelect" onchange="selectScenario()"><option value="">Create a scenario</option></select></div>
          <div class="guard-field"><label for="scenarioName">Scenario name</label><input id="scenarioName" placeholder="Cold launch and browse"></div>
          <div class="guard-field"><label for="scenarioId">Scenario ID</label><input id="scenarioId" placeholder="cold-launch"></div>
          <div class="guard-field"><label for="scenarioRevision">Revision</label><input id="scenarioRevision" value="1" readonly></div>
          <div class="guard-field"><label for="scenarioWarmups">Warm-ups</label><input id="scenarioWarmups" type="number" min="0" max="20" value="1"></div>
          <div class="guard-field"><label for="scenarioRepetitions">Measured repetitions</label><input id="scenarioRepetitions" type="number" min="1" max="100" value="3"></div>
        </div>

        <div class="guard-divider"></div>
        <div class="guard-toolbar"><div><h3 class="studio-section-title">Journey steps</h3><p class="studio-section-copy">Semantic selectors travel across resolutions; normalized coordinates are an explicit fallback.</p></div><button class="studio-button" type="button" onclick="addStep()">Add step</button></div>
        <div class="guard-list" id="stepList"></div>

        <div class="guard-divider"></div>
        <div class="guard-toolbar"><div><h3 class="studio-section-title">Performance budgets</h3><p class="studio-section-copy">A critical violation blocks the release; a warning asks for review.</p></div><button class="studio-button" type="button" onclick="addBudget()">Add budget</button></div>
        <div class="guard-list" id="budgetList"></div>
      </section>
    </div>

    <aside class="guard-stack guard-sticky">
      <section class="studio-panel guard-card" aria-labelledby="targetTitle">
        <div class="guard-toolbar"><div><h2 class="studio-section-title" id="targetTitle">Target and readiness</h2><p class="studio-section-copy">Capabilities are probed from the selected device, not inferred from its brand.</p></div><button class="studio-button" type="button" onclick="loadDevices(true)">Refresh</button></div>
        <div class="guard-field"><label for="deviceSelect">ADB target</label><select id="deviceSelect" onchange="probeCapabilities()"><option value="">Select a device</option></select></div>
        <div class="guard-capabilities" id="capabilityList"></div>
        <div class="guard-readiness" id="readiness"></div>
        <button class="studio-button primary guard-run" id="runButton" type="button" onclick="runReleaseCheck()">Run release check</button>
        <div class="guard-toast" id="toast" role="status" aria-live="polite"></div>

        <div class="guard-progress" id="runProgress">
          <div class="guard-progress-track"><div class="guard-progress-bar" id="runProgressBar"></div></div>
          <div class="guard-progress-copy" id="runProgressCopy">Preparing…</div>
          <div class="guard-toolbar-actions" style="margin-top:10px"><a class="studio-link" href="/dashboard">Open live dashboard</a><button class="studio-button danger" type="button" onclick="stopReleaseCheck()">Stop</button></div>
        </div>
        <div class="guard-result" id="resultCard">
          <div class="guard-verdict" id="resultVerdict"></div>
          <div class="guard-item-meta" id="resultMeta"></div>
          <div class="guard-findings" id="resultFindings"></div>
          <div class="guard-artifacts" id="resultArtifacts"></div>
          <div class="guard-toolbar-actions" style="margin-top:12px"><button class="studio-button success" id="baselineButton" type="button" onclick="promoteBaseline()">Use as baseline</button><a class="studio-link" href="/compare">Compare runs</a></div>
        </div>
      </section>
    </aside>
  </div>
</main>
<section class="guard-viewer" id="guardViewer" role="dialog" aria-modal="true" aria-label="Release Guard evidence" hidden><div class="guard-viewer-bar"><strong id="guardViewerTitle">Evidence</strong><button class="studio-button" type="button" onclick="closeGuardArtifact()">Back to result</button></div><iframe class="guard-viewer-frame" id="guardViewerFrame" title="Release Guard evidence"></iframe></section>
<script>
var DRAFT_KEY = 'aps.releaseGuard.draft.v1';
var projects = [], scenarios = [], steps = [], budgets = [], target = null, loadedScenario = null, latestManifest = null, statusTimer = null, restoredDraftValues = {};
var STEP_TYPES = [
  ['launchApp','Launch app'],['launchDeepLink','Launch deep link'],['forceStopApp','Force-stop app'],['clearAppData','Clear app data'],['installApk','Install APK'],['uninstallPackage','Uninstall package'],
  ['grantPermission','Grant permission'],['wait','Wait'],['beginSection','Begin measured section'],['endSection','End measured section'],['pressKey','Press key'],
  ['tapElement','Tap UI element'],['tapAt','Tap normalized coordinate'],['swipe','Swipe'],['enterText','Enter text'],['waitForElement','Wait for UI element'],
  ['waitForActivity','Wait for activity'],['waitForLog','Wait for log pattern'],['waitForProcess','Wait for process'],['captureScreenshot','Capture screenshot'],['shell','Advanced ADB shell']
];
var METRICS = [
  ['cpu_util_pct','CPU utilization','%'],['memory_used_mb','Memory used','MB'],['memory_used_pct','Memory used','%'],['app_pss_mb','App PSS','MB'],
  ['jank_pct','Janky frames','%'],['main_thread_cpu_pct','Main-thread CPU','%'],['app_cpu_pct','App CPU','%'],['cpu_temp_c','CPU temperature','°C'],
  ['psi_cpu','CPU pressure','%'],['psi_memory','Memory pressure','%'],['screen_fps','Screen FPS','fps'],['video_fps','Video FPS','fps'],
  ['section_duration_ms','Section duration','ms'],['anr_count','ANR count','count'],['crash_count','Crash count','count']
];

function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g,function(ch){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch];}); }
function option(value,label,selected){return '<option value="'+esc(value)+'"'+(value===selected?' selected':'')+'>'+esc(label)+'</option>';}
function slug(value){return String(value||'').toLowerCase().trim().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'');}
function toast(message,bad){var el=document.getElementById('toast');el.textContent=message||'';el.className='guard-toast'+(bad?' bad':'');}
function api(url,options){return fetch(url,options).then(function(r){return r.json().then(function(body){if(!r.ok||body.ok===false)throw new Error(body.error||('Request failed: '+r.status));return body;});});}
function postJson(url,value){return api(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value)});}

function saveDraft(){
  var values={}; Array.prototype.forEach.call(document.querySelectorAll('input,select'),function(el){if(el.id)values[el.id]=el.type==='checkbox'?el.checked:el.value;});
  localStorage.setItem(DRAFT_KEY,JSON.stringify({values:values,steps:steps,budgets:budgets}));
}
function restoreDraft(){
  try{var d=JSON.parse(localStorage.getItem(DRAFT_KEY)||'null');if(!d)return;steps=d.steps||[];budgets=d.budgets||[];restoredDraftValues=d.values||{};Object.keys(restoredDraftValues).forEach(function(id){var el=document.getElementById(id);if(el){if(el.type==='checkbox')el.checked=!!restoredDraftValues[id];else el.value=restoredDraftValues[id];}});}catch(e){}
}
function bindDraft(){Array.prototype.forEach.call(document.querySelectorAll('input,select'),function(el){el.addEventListener('input',saveDraft);el.addEventListener('change',saveDraft);});}

function newProject(){document.getElementById('projectSelect').value='';document.getElementById('projectName').value='';document.getElementById('projectId').value='';document.getElementById('projectPackage').value='';newScenario();saveDraft();}
function saveProject(){
  var name=document.getElementById('projectName').value.trim(), id=document.getElementById('projectId').value.trim()||slug(name), pkg=document.getElementById('projectPackage').value.trim();
  if(!name||!id||!pkg){toast('Project name, ID and Android package are required.',true);return Promise.reject(new Error('Invalid project'));}
  return postJson('/api/release/projects',{id:id,name:name,packageName:pkg}).then(function(d){toast('Project saved.');return loadProjects(d.project.id);}).catch(function(e){toast(e.message,true);throw e;});
}
function loadProjects(selectId,hydrate){return api('/api/release/projects').then(function(d){projects=d.projects||[];var select=document.getElementById('projectSelect');select.innerHTML='<option value="">Create a project</option>'+projects.map(function(p){return option(p.id,p.name,selectId);}).join('');if(selectId&&projects.some(function(p){return p.id===selectId;})){select.value=selectId;if(hydrate)selectProject();else loadScenarios(selectId,restoredDraftValues.scenarioSelect||'',false);}});}
function selectProject(){var id=document.getElementById('projectSelect').value,p=projects.filter(function(x){return x.id===id;})[0];if(!p)return;document.getElementById('projectName').value=p.name;document.getElementById('projectId').value=p.id;document.getElementById('projectPackage').value=p.packageName;loadScenarios(p.id);saveDraft();}

function newScenario(){loadedScenario=null;document.getElementById('scenarioSelect').value='';document.getElementById('scenarioName').value='';document.getElementById('scenarioId').value='';document.getElementById('scenarioRevision').value='1';document.getElementById('scenarioWarmups').value='1';document.getElementById('scenarioRepetitions').value='3';steps=[{type:'clearAppData'},{type:'beginSection',section:'cold_launch'},{type:'launchApp'},{type:'wait',durationMs:3000},{type:'endSection',section:'cold_launch'}];budgets=[defaultBudget()];renderSteps();renderBudgets();saveDraft();}
function loadScenarios(projectId,selectId,hydrate){return api('/api/release/scenarios?projectId='+encodeURIComponent(projectId)).then(function(d){scenarios=d.scenarios||[];var s=document.getElementById('scenarioSelect');s.innerHTML='<option value="">Create a scenario</option>'+scenarios.map(function(x){return option(x.id,x.name+' · r'+x.revision,selectId);}).join('');if(selectId&&scenarios.some(function(x){return x.id===selectId;})){s.value=selectId;if(hydrate!==false)selectScenario();}});}
function selectScenario(){var id=document.getElementById('scenarioSelect').value,s=scenarios.filter(function(x){return x.id===id;})[0];if(!s)return;loadedScenario=s;document.getElementById('scenarioName').value=s.name;document.getElementById('scenarioId').value=s.id;document.getElementById('scenarioRevision').value=String(s.revision);document.getElementById('scenarioWarmups').value=String(s.warmups);document.getElementById('scenarioRepetitions').value=String(s.repetitions);steps=JSON.parse(JSON.stringify(s.steps||[]));budgets=JSON.parse(JSON.stringify(s.budgets||[]));renderSteps();renderBudgets();saveDraft();updateReadiness();}
function scenarioValue(){
  var name=document.getElementById('scenarioName').value.trim(),id=document.getElementById('scenarioId').value.trim()||slug(name),pkg=document.getElementById('projectPackage').value.trim();
  var value={schemaVersion:1,id:id,revision:loadedScenario?loadedScenario.revision:1,name:name,packageName:pkg,repetitions:Number(document.getElementById('scenarioRepetitions').value||1),warmups:Number(document.getElementById('scenarioWarmups').value||0),steps:steps,budgets:budgets,capture:{metrics:[],requiredCapabilities:[]}};
  if(loadedScenario){var old=JSON.parse(JSON.stringify(loadedScenario));old.revision=value.revision;var candidate=JSON.parse(JSON.stringify(value));if(JSON.stringify(old)!==JSON.stringify(candidate))value.revision=loadedScenario.revision+1;}
  return value;
}
function saveScenario(){
  return saveProject().then(function(){var projectId=document.getElementById('projectId').value.trim();
  var value=scenarioValue();if(!value.name||!value.id||!value.packageName||!value.steps.length){toast('Scenario name, ID, package and at least one step are required.',true);return Promise.reject(new Error('Invalid scenario'));}
  return fetch('/api/release/scenarios?projectId='+encodeURIComponent(projectId),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(value)}).then(function(r){return r.json().then(function(d){if(!r.ok)throw new Error(d.error||'Could not save scenario');return d;});}).then(function(d){loadedScenario=d.scenario;document.getElementById('scenarioRevision').value=String(d.scenario.revision);toast('Scenario revision '+d.scenario.revision+' saved.');saveDraft();return loadScenarios(projectId,d.scenario.id,false).then(function(){return d.scenario;});}).catch(function(e){toast(e.message,true);throw e;});});
}

function addStep(){steps.push({type:'launchApp'});renderSteps();saveDraft();}
function moveStep(index,delta){var to=index+delta;if(to<0||to>=steps.length)return;var item=steps.splice(index,1)[0];steps.splice(to,0,item);renderSteps();saveDraft();}
function removeStep(index){steps.splice(index,1);renderSteps();saveDraft();updateReadiness();}
function setStepType(index,type){steps[index]=defaultStep(type);renderSteps();saveDraft();updateReadiness();}
function defaultStep(type){if(type==='launchDeepLink')return{type:type,uri:''};if(type==='wait')return{type:type,durationMs:1000};if(type==='pressKey')return{type:type,key:'DPAD_CENTER'};if(type==='tapAt')return{type:type,xPct:.5,yPct:.5};if(type==='swipe')return{type:type,fromXPct:.5,fromYPct:.8,toXPct:.5,toYPct:.2,durationMs:300};if(type==='beginSection'||type==='endSection')return{type:type,section:'measured'};if(type==='waitForProcess')return{type:type,timeoutMs:15000};if(type==='captureScreenshot')return{type:type,fileName:'evidence.png'};return{type:type};}
function stepInput(index,key,label,value,type){return '<div class="guard-field"><label>'+esc(label)+'</label><input '+(type||'')+' value="'+esc(value==null?'':value)+'" oninput="setStep('+index+',\''+key+'\',this.type===\'number\'?Number(this.value):this.value)"></div>';}
function selectorFields(index,step){var s=step.selector||{};return stepInput(index,'selector.resourceId','Resource ID',s.resourceId||'')+stepInput(index,'selector.contentDescription','Accessibility description',s.contentDescription||'')+stepInput(index,'selector.text','Visible text',s.text||'')+stepInput(index,'selector.fallbackXPct','Fallback X',s.fallbackXPct==null?'':s.fallbackXPct,'type="number" min="0" max="1" step="0.01"')+stepInput(index,'selector.fallbackYPct','Fallback Y',s.fallbackYPct==null?'':s.fallbackYPct,'type="number" min="0" max="1" step="0.01"');}
function setStep(index,key,value){var path=key.split('.'),obj=steps[index];for(var i=0;i<path.length-1;i++){obj[path[i]]=obj[path[i]]||{};obj=obj[path[i]];}obj[path[path.length-1]]=value;saveDraft();updateReadiness();}
function stepFields(index,s){var t=s.type;if(t==='installApk')return stepInput(index,'path','APK path',s.path||'');if(t==='launchDeepLink')return stepInput(index,'uri','Deep link URI',s.uri||'','type="url" maxlength="4096" required placeholder="myapp://content/123?source=release-guard"');if(t==='grantPermission')return stepInput(index,'permission','Permission',s.permission||'');if(t==='wait')return stepInput(index,'durationMs','Duration (ms)',s.durationMs,'type="number" min="1"');if(t==='beginSection'||t==='endSection')return stepInput(index,'section','Section',s.section||'');if(t==='pressKey')return '<div class="guard-field"><label>Logical key</label><select onchange="setStep('+index+',\'key\',this.value)">'+['BACK','HOME','ENTER','DPAD_UP','DPAD_DOWN','DPAD_LEFT','DPAD_RIGHT','DPAD_CENTER','CHANNEL_UP','CHANNEL_DOWN','MEDIA_PLAY_PAUSE'].map(function(k){return option(k,k,s.key);}).join('')+'</select></div>';if(t==='tapAt')return stepInput(index,'xPct','X percent',s.xPct,'type="number" min="0" max="1" step="0.01"')+stepInput(index,'yPct','Y percent',s.yPct,'type="number" min="0" max="1" step="0.01"');if(t==='swipe')return stepInput(index,'fromXPct','From X',s.fromXPct,'type="number" min="0" max="1" step="0.01"')+stepInput(index,'fromYPct','From Y',s.fromYPct,'type="number" min="0" max="1" step="0.01"')+stepInput(index,'toXPct','To X',s.toXPct,'type="number" min="0" max="1" step="0.01"')+stepInput(index,'toYPct','To Y',s.toYPct,'type="number" min="0" max="1" step="0.01"')+stepInput(index,'durationMs','Duration (ms)',s.durationMs||300,'type="number" min="1"');if(t==='enterText')return stepInput(index,'text','Text',s.text||'');if(t==='tapElement'||t==='waitForElement')return selectorFields(index,s)+(t==='waitForElement'?stepInput(index,'timeoutMs','Timeout (ms)',s.timeoutMs||15000,'type="number" min="1"'):'');if(t==='waitForActivity')return stepInput(index,'activity','Activity',s.activity||'')+stepInput(index,'timeoutMs','Timeout (ms)',s.timeoutMs||15000,'type="number" min="1"');if(t==='waitForLog')return stepInput(index,'pattern','Regex pattern',s.pattern||'')+stepInput(index,'timeoutMs','Timeout (ms)',s.timeoutMs||15000,'type="number" min="1"');if(t==='waitForProcess')return stepInput(index,'timeoutMs','Timeout (ms)',s.timeoutMs||15000,'type="number" min="1"');if(t==='captureScreenshot')return stepInput(index,'fileName','File name',s.fileName||'evidence.png');if(t==='shell')return stepInput(index,'command','ADB shell command',s.command||'')+stepInput(index,'timeoutMs','Timeout (ms)',s.timeoutMs||20000,'type="number" min="1"');return ''}
function renderSteps(){var el=document.getElementById('stepList');if(!steps.length){el.innerHTML='<div class="guard-empty">Add at least one journey step.</div>';return;}el.innerHTML=steps.map(function(s,i){return '<article class="guard-item"><div class="guard-item-head"><div><div class="guard-item-title">Step '+(i+1)+'</div><div class="guard-item-meta">'+esc(s.type)+'</div></div><div class="guard-item-actions"><button class="guard-icon-button" type="button" onclick="moveStep('+i+',-1)" aria-label="Move step up">↑</button><button class="guard-icon-button" type="button" onclick="moveStep('+i+',1)" aria-label="Move step down">↓</button><button class="guard-icon-button" type="button" onclick="removeStep('+i+')" aria-label="Remove step">×</button></div></div><div class="guard-row"><div class="guard-field span"><label>Step type</label><select onchange="setStepType('+i+',this.value)">'+STEP_TYPES.map(function(x){return option(x[0],x[1],s.type);}).join('')+'</select></div>'+stepFields(i,s)+'</div></article>';}).join('');}

function defaultBudget(){return{id:'cpu-p95',name:'CPU p95',metric:{id:'cpu_util_pct',unit:'%',normalizedAcrossDevices:true},aggregation:'P95',direction:'MAXIMUM',section:null,absoluteLimit:80,relativeRegressionLimitPct:15,severity:'CRITICAL',required:true};}
function addBudget(){budgets.push(defaultBudget());renderBudgets();saveDraft();}
function removeBudget(index){budgets.splice(index,1);renderBudgets();saveDraft();updateReadiness();}
function setBudget(index,path,value){var parts=path.split('.'),obj=budgets[index];for(var i=0;i<parts.length-1;i++){obj[parts[i]]=obj[parts[i]]||{};obj=obj[parts[i]];}obj[parts[parts.length-1]]=value;saveDraft();updateReadiness();}
function budgetInput(i,path,label,value,type){return '<div class="guard-field"><label>'+esc(label)+'</label><input '+(type||'')+' value="'+esc(value==null?'':value)+'" oninput="setBudget('+i+',\''+path+'\',this.type===\'number\'?(this.value===\'\'?null:Number(this.value)):this.value)"></div>';}
function renderBudgets(){var el=document.getElementById('budgetList');if(!budgets.length){el.innerHTML='<div class="guard-empty">No budgets yet. Add one to turn the journey into a release gate.</div>';return;}el.innerHTML=budgets.map(function(b,i){var metric=b.metric||{};return '<article class="guard-item"><div class="guard-item-head"><div><div class="guard-item-title">'+esc(b.name||('Budget '+(i+1)))+'</div><div class="guard-item-meta">'+esc(metric.id||'metric')+' · '+esc(b.aggregation||'MEAN')+'</div></div><button class="guard-icon-button" type="button" onclick="removeBudget('+i+')" aria-label="Remove budget">×</button></div><div class="guard-row">'+budgetInput(i,'name','Budget name',b.name||'')+budgetInput(i,'id','Budget ID',b.id||'')+'<div class="guard-field"><label>Metric</label><select onchange="var value=this.value;var m=METRICS.filter(function(x){return x[0]===value;})[0];setBudget('+i+',\'metric\',{id:value,unit:m?m[2]:null,normalizedAcrossDevices:true})">'+METRICS.map(function(m){return option(m[0],m[1],metric.id);}).join('')+'</select></div><div class="guard-field"><label>Aggregation</label><select onchange="setBudget('+i+',\'aggregation\',this.value)">'+['MINIMUM','MAXIMUM','MEAN','MEDIAN','P90','P95','COUNT','RATE','GROWTH_PERCENT'].map(function(x){return option(x,x,b.aggregation);}).join('')+'</select></div>'+budgetInput(i,'section','Measured section',b.section||'')+budgetInput(i,'absoluteLimit','Absolute limit',b.absoluteLimit,'type="number" step="0.01"')+budgetInput(i,'relativeRegressionLimitPct','Allowed regression %',b.relativeRegressionLimitPct,'type="number" min="0" step="0.1"')+'<div class="guard-field"><label>Severity</label><select onchange="setBudget('+i+',\'severity\',this.value)">'+option('CRITICAL','Block release',b.severity)+option('WARNING','Review warning',b.severity)+'</select></div></div></article>';}).join('');}

function loadDevices(refresh){return api('/api/devices').then(function(d){var list=(d.devices||[]).filter(function(x){return x.state==='device';});var select=document.getElementById('deviceSelect'),current=select.value||restoredDraftValues.deviceSelect||'';select.innerHTML='<option value="">Select a device</option>'+list.map(function(x){return option(x.serial,(x.desc||x.serial)+' · '+x.serial,current);}).join('');if(current&&list.some(function(x){return x.serial===current;}))select.value=current;if(refresh||select.value)probeCapabilities();updateReadiness();}).catch(function(e){toast(e.message,true);});}
function probeCapabilities(){var serial=document.getElementById('deviceSelect').value;if(!serial){target=null;renderCapabilities();updateReadiness();return;}api('/api/release/capabilities?serial='+encodeURIComponent(serial)+'&refresh=true').then(function(d){target=d.target;renderCapabilities();updateReadiness();saveDraft();}).catch(function(e){target=null;toast(e.message,true);renderCapabilities();updateReadiness();});}
function renderCapabilities(){var el=document.getElementById('capabilityList'),caps=target?target.capabilities||[]:[];el.innerHTML=caps.length?caps.map(function(c){return '<span class="guard-chip on">'+esc(c)+'</span>';}).join(''):'<span class="guard-chip">No device capabilities loaded</span>';}
function requiredCapabilities(){var out={SHELL:true,LOGCAT:true};steps.forEach(function(s){if(['installApk','uninstallPackage'].indexOf(s.type)>=0)out.PACKAGE_INSTALL=true;if(['tapElement','waitForElement'].indexOf(s.type)>=0)out.UI_HIERARCHY=true;if(['tapElement','tapAt','swipe','enterText'].indexOf(s.type)>=0)out.TOUCH_INPUT=true;if(s.type==='pressKey'&&String(s.key).indexOf('DPAD')===0)out.DPAD_INPUT=true;if(s.type==='captureScreenshot')out.SCREEN_CAPTURE=true;});budgets.forEach(function(b){var id=(b.metric&&b.metric.id)||'';if(id==='jank_pct')out.GFXINFO=true;if(id.indexOf('psi_')===0)out.PSI=true;if(id==='cpu_temp_c')out.THERMAL=true;});return Object.keys(out);}
function updateReadiness(){var required=requiredCapabilities(),available=target?target.capabilities||[]:[],missing=required.filter(function(x){return available.indexOf(x)<0;});var checks=[{ok:!!document.getElementById('projectId').value.trim(),text:'Project configured'},{ok:!!document.getElementById('scenarioName').value.trim()&&steps.length>0,text:'Scenario has '+steps.length+' step(s)'},{ok:budgets.length>0,text:budgets.length+' performance budget(s)'},{ok:!!target,text:'Authorized target selected'},{ok:!!target&&!missing.length,text:missing.length?('Missing: '+missing.join(', ')):'Required device capabilities available'}];document.getElementById('readiness').innerHTML=checks.map(function(c){return '<div class="guard-check '+(c.ok?'ok':'bad')+'"><span class="guard-check-dot"></span><span>'+esc(c.text)+'</span></div>';}).join('');document.getElementById('runButton').disabled=checks.some(function(c){return !c.ok;});}

function runReleaseCheck(){saveScenario().then(function(s){var payload={projectId:document.getElementById('projectId').value.trim(),scenarioId:s.id,serial:document.getElementById('deviceSelect').value,allowCompatibleDeviceBaseline:false};return postJson('/api/release/start',payload);}).then(function(){latestManifest=null;document.getElementById('runProgress').classList.add('show');document.getElementById('resultCard').classList.remove('show');document.getElementById('runButton').disabled=true;pollStatus();}).catch(function(e){toast(e.message,true);});}
function pollStatus(){clearTimeout(statusTimer);api('/api/release/status').then(function(d){var p=d.progress||{},m=d.manifest;var total=Math.max(1,p.totalAttempts||1),attempt=p.attempt||0,stepTotal=Math.max(1,p.totalSteps||1),step=p.step||0;var pct=Math.min(96,Math.round(((Math.max(0,attempt-1)+step/stepTotal)/total)*100));if(!d.running&&m&&['PASSED','WARNING','FAILED','CANCELLED'].indexOf(m.state)>=0)pct=100;document.getElementById('runProgressBar').style.width=Math.max(4,pct)+'%';document.getElementById('runProgressCopy').textContent=(p.state||'DISCOVER')+' · '+(p.message||'Working');if(m)latestManifest=m;if(d.running){statusTimer=setTimeout(pollStatus,600);}else if(m){showResult(m);document.getElementById('runButton').disabled=false;}else{statusTimer=setTimeout(pollStatus,500);}}).catch(function(e){toast(e.message,true);statusTimer=setTimeout(pollStatus,1200);});}
function stopReleaseCheck(){postJson('/api/release/stop',{}).then(function(){toast('Stopping after the current safe boundary…');});}
function showResult(m){latestManifest=m;var e=m.evaluation||{verdict:m.state,results:[]},verdict=String(e.verdict||m.state).toLowerCase();var card=document.getElementById('resultCard');card.classList.add('show');var title={pass:'Release ready',warning:'Review required',fail:'Release blocked',cancelled:'Run cancelled'}[verdict]||m.state;var v=document.getElementById('resultVerdict');v.className='guard-verdict '+verdict;v.textContent=title;document.getElementById('resultMeta').textContent=m.runId+' · '+m.attempts.length+' attempt(s)';var results=(e.results||[]).slice().sort(function(a,b){var rank={FAIL:0,WARNING:1,NOT_EVALUATED:2,PASS:3};return(rank[a.status]||9)-(rank[b.status]||9);});document.getElementById('resultFindings').innerHTML=results.length?results.map(function(r){return '<div class="guard-finding"><span><strong>'+esc(r.name)+'</strong><br>'+esc(r.message)+'</span><span>'+esc(r.status)+(r.value==null?'':(' · '+Number(r.value).toFixed(2)))+'</span></div>';}).join(''):'<div class="guard-finding"><span>No explicit budget findings</span><span>'+esc(e.verdict)+'</span></div>';var labels={'report.html':'Open result report','report.pdf':'Open PDF','evaluation.json':'Evaluation JSON','result.junit.xml':'JUnit XML','summary.csv':'Summary CSV'},buttons=Object.keys(labels).filter(function(key){return m.artifacts&&m.artifacts[key];}).map(function(key){return{key:key,label:labels[key]};});Object.keys(m.artifacts||{}).filter(function(key){return /^attempt-\d+\/logcat\.log$/.test(key);}).sort().forEach(function(key,index){buttons.push({key:key,label:'Open full logcat · attempt '+(index+1)});});document.getElementById('resultArtifacts').innerHTML=buttons.map(function(item){return '<button class="studio-button" type="button" onclick="openGuardArtifact(\''+item.key+'\')">'+esc(item.label)+'</button>';}).join('');document.getElementById('baselineButton').disabled=!m.attempts.some(function(a){return a.successful;});}
function openGuardArtifact(key){if(!latestManifest||!latestManifest.artifacts[key])return;document.getElementById('guardViewerTitle').textContent=key;document.getElementById('guardViewerFrame').src='/api/release/artifact?runId='+encodeURIComponent(latestManifest.runId)+'&key='+encodeURIComponent(key);document.getElementById('guardViewer').hidden=false;document.querySelector('main').inert=true;}
function closeGuardArtifact(){document.getElementById('guardViewer').hidden=true;document.getElementById('guardViewerFrame').src='about:blank';document.querySelector('main').inert=false;}
function promoteBaseline(){if(!latestManifest)return;postJson('/api/release/baseline',{runId:latestManifest.runId}).then(function(){toast('Baseline promoted. Future checks will compare against this run.');}).catch(function(e){toast(e.message,true);});}

restoreDraft();if(!steps.length)newScenario();else{renderSteps();renderBudgets();}bindDraft();loadProjects(restoredDraftValues.projectSelect||'',false);loadDevices(false);renderCapabilities();updateReadiness();api('/api/release/status').then(function(d){if(d.running||d.manifest){document.getElementById('runProgress').classList.add('show');pollStatus();}}).catch(function(){});
</script>
</body>
</html>"""
