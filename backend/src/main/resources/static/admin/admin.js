'use strict';
const $ = id => document.getElementById(id);
const tg = window.Telegram?.WebApp;
const initData = tg?.initData || '';
const state = { view: 'posts', page: 0, size: 20, total: 0, items: [], post: null, ready: false };
const labels = {
  VACANCY:'Вакансия',EVENT:'Мероприятие',PRACTICE:'Практика',DEADLINE:'Дедлайн',ANNOUNCEMENT:'Объявление',OTHER:'Другое',
  ACTIVE:'Актуально',UNKNOWN:'Срок не определён',EXPIRED:'Срок истёк',INVALID:'Проверить дату',ARCHIVED:'В архиве',
  PENDING:'В очереди',PROCESSING:'Обработка',REVIEW_REQUIRED:'На проверке',FAILED:'Ошибка',CLASSIFIED:'Определено',
  NOT_REQUIRED:'Подтверждено',AUTO_APPROVED:'Подтверждено AI',APPROVED:'Подтверждено',NO_RELATION:'Связь не найдена',REJECTED:'Отклонено',SUPERSEDED:'Устарело',
  UPDATE:'Дополнение',CORRECTION:'Исправление',CANCELLATION:'Отмена',MIXED:'Несколько изменений',UNCLASSIFIED:'Не определено',
  COMPLETED:'Готово',RUNNING:'Выполняется',QUEUED:'В очереди',MANUAL:'Вручную',TELEGRAM_REPLY:'Ответ в Telegram',
  STANDALONE_INFERRED:'Связь по содержанию',ADMIN_CONFIRMED:'Подтверждено сотрудником',SYSTEM_BACKFILL:'Восстановлено системой'
};
const viewInfo = {
  posts:['Публикации канала','Актуальные возможности для студентов — под вашим контролем.','Все публикации'],
  faqs:['База знаний','Проверенные ответы на вопросы о практике, трудоустройстве и работе центра.','Вопросы и ответы'],
  relations:['Связи публикаций','Уточнения, переносы и отмены, которые бот учитывает в своих ответах.','Связанные публикации'],
  candidates:['Проверка связей','Помогите CareerAI правильно связать самостоятельные публикации.','Предложенные связи'],
  jobs:['Обслуживание','Обновление поискового индекса и обработка ожидающих уточнений.','Фоновые операции'],
  audit:['Журнал действий','История изменений, внесённых администраторами центра.','Последние изменения']
};
const esc = value => String(value ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const date = value => value ? new Intl.DateTimeFormat('ru',{dateStyle:'medium',timeStyle:'short'}).format(new Date(value)) : 'Не указан';
const categoryIcon = type => ({VACANCY:'▣',EVENT:'◇',PRACTICE:'↗',DEADLINE:'◷'}[type] || '▤');
function badge(status) {
  const tone = ['ACTIVE','COMPLETED','APPROVED','AUTO_APPROVED','CLASSIFIED','NOT_REQUIRED'].includes(status)?'green':
    ['UNKNOWN','REVIEW_REQUIRED','PENDING','QUEUED'].includes(status)?'amber':['INVALID','FAILED'].includes(status)?'red':['PROCESSING','RUNNING'].includes(status)?'blue':'gray';
  return `<span class="badge ${tone}">${esc(labels[status] || status)}</span>`;
}
function postStatus(p) {return p.is_archived?'ARCHIVED':p.expires_at&&new Date(p.expires_at)<=new Date()?'EXPIRED':p.freshness_status;}
function toast(text) {$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toast.timer);toast.timer=setTimeout(()=>$('toast').hidden=true,5500);}
async function api(path,options={}) {
  const response = await fetch('/api/admin'+path,{...options,headers:{'Authorization':'tma '+initData,...(options.body?{'Content-Type':'application/json'}:{}),...options.headers},cache:'no-store'});
  let data; try {data=await response.json();}catch {data={};}
  if(!response.ok) {
    if(response.status===401) authError(data.message);
    throw new Error(data.message || (response.status===404?'Раздел или запись недоступны':'Не удалось выполнить запрос. Повторите позже.'));
  }
  return data;
}
const post = (path,data) => api(path,{method:'POST',...(data?{body:JSON.stringify(data)}:{})});
function authError(message) {state.ready=false;$('workspace-content').hidden=true;$('auth-state').hidden=false;$('auth-message').textContent=message||'Откройте /admin в личном чате с ботом. Если доступ ещё не настроен, узнайте ID командой /myid и передайте его ответственному за запуск.';$('connection-label').textContent='Нет доступа';}
async function start() {
  if(!initData){authError();return;}
  try {
    tg?.ready();tg?.expand();
    const who=await api('/me');state.ready=true;$('auth-state').hidden=true;$('workspace-content').hidden=false;
    $('profile-name').textContent=who.firstName;$('profile-name').insertAdjacentHTML('beforeend','<small>Администратор центра</small>');
    $('avatar').textContent=who.firstName.slice(0,1).toUpperCase();$('connection-label').textContent='Подключено';
    await refresh();
  }catch(e){authError(e.message);}
}
async function refreshOverview() {
  const data=await api('/overview');
  for(const [key,id] of Object.entries({posts:'stat-posts',current:'stat-current',review:'stat-review',faq:'stat-faq'}))$(id).textContent=data[key];
  $('nav-count').textContent=data.posts;
}
function loading(){ $('content').innerHTML='<div class="empty">Загрузка…</div>';$('prev').disabled=true;$('next').disabled=true; }
function empty(title='Пока ничего нет',text='Здесь появятся записи, когда они поступят в CareerAI.') {return `<div class="empty"><strong>${esc(title)}</strong>${esc(text)}</div>`;}
async function refresh(){if(!state.ready)return;await Promise.all([refreshOverview(),load()]).catch(e=>toast(e.message));}
let loadSequence = 0;
async function load() {
  const sequence = ++loadSequence;
  const view=state.view;loading();
  try {
    let data;
    const params=new URLSearchParams({page:state.page,size:state.size});
    if(view==='posts') {
      params.set('q',$('search').value);params.set('status',$('status-filter').value);params.set('type',$('type-filter').value);
      params.set('from',$('date-from').value);params.set('to',$('date-to').value);
    }else if(view==='faqs')params.set('q',$('search').value);
    data=await api('/'+view+'?'+params);
    if(view!==state.view || sequence!==loadSequence)return;
    state.items=Array.isArray(data)?data:(data.items||data.content||[]);state.total=Array.isArray(data)?data.length:(data.total??data.totalElements??0);
    $('result-count').textContent=state.total+' записей';$('page-label').textContent=`Страница ${state.page+1} из ${Math.max(1,Math.ceil(state.total/state.size))}`;
    $('prev').disabled=state.page===0;$('next').disabled=(state.page+1)*state.size>=state.total||Array.isArray(data);
    $('content').innerHTML=({posts:renderPosts,faqs:renderFaqs,relations:renderRelations,candidates:renderCandidates,jobs:renderJobs,audit:renderAudit}[view])(state.items);
  }catch(e){if(sequence!==loadSequence)return;$('content').innerHTML=empty('Не удалось загрузить данные',e.message);$('result-count').textContent='Ошибка загрузки';}
}
function renderPosts(items) {
  if(!items.length)return empty('Публикации не найдены','Измените фильтры или дождитесь новых публикаций в подключённом канале.');
  return `<div class="table-wrap"><table class="posts"><thead><tr><th>ПУБЛИКАЦИЯ</th><th>КАТЕГОРИЯ</th><th>СТАТУС</th><th>ОПУБЛИКОВАНО</th><th></th></tr></thead><tbody>${items.map(p=>`<tr><td><div class="post-cell"><span class="post-icon">${categoryIcon(p.post_type)}</span><div><div class="post-title">${esc(p.title||p.text?.split('\n')[0]?.slice(0,90)||'Публикация #'+p.id)}</div><div class="post-preview">${esc(p.channel_title||'Telegram-канал')} · ${esc(p.text?.slice(0,140))}</div></div></td><td><span class="badge blue">${esc(labels[p.post_type]||'Не определена')}</span></td><td>${badge(postStatus(p))}</td><td>${esc(date(p.posted_at))}</td><td><button class="row-button" data-action="view-post" data-id="${p.id}">Открыть ↗</button></td></tr>`).join('')}</tbody></table></div>`;
}
function renderFaqs(items) {
  if(!items.length)return empty('Вопросы не найдены','Добавьте проверенный ответ, чтобы он стал доступен студентам.');
  return `<div class="table-wrap"><table><thead><tr><th>ВОПРОС И ОТВЕТ</th><th>КАТЕГОРИЯ</th><th>СТАТУС</th><th></th></tr></thead><tbody>${items.map(f=>`<tr><td><div class="post-title">${esc(f.question)}</div><div class="post-preview">${esc(f.short_answer)}</div></td><td>${esc(f.category)}</td><td>${badge(f.is_active?'ACTIVE':'ARCHIVED')}</td><td><button class="row-button" data-action="edit-faq" data-id="${f.id}">Изменить</button></td></tr>`).join('')}</tbody></table></div>`;
}
function renderRelations(items) {
  if(!items.length)return empty('Связей пока нет','Связи появятся после получения уточнений и ответов на публикации канала.');
  return `<div class="review-list">${items.map(r=>`<article class="review-card"><div class="review-card-header"><h3>Связь #${r.id} · ${esc(labels[r.relation_type])}</h3>${badge(r.classification_status)}</div><div class="review-texts"><div><small>УТОЧНЕНИЕ · #${r.source_post_id}</small>${esc(r.source_text)}</div><div><small>ОСНОВНАЯ ПУБЛИКАЦИЯ · #${r.target_post_id}</small>${esc(r.target_text)}</div></div><p class="review-reason">${esc(r.proposed_reason||r.reason||'Описание пока отсутствует')}</p><span class="muted">${esc(labels[r.relation_origin]||r.relation_origin)}${r.classification_confidence!=null?' · Уверенность '+Math.round(r.classification_confidence*100)+'%':''}</span><div class="actions"><button class="btn primary compact" data-action="confirm-relation" data-id="${r.id}">Проверить и подтвердить</button><button class="btn compact" data-action="retry-relation" data-id="${r.id}" ${!['TELEGRAM_REPLY','SYSTEM_BACKFILL'].includes(r.relation_origin)?'disabled':''}>Повторить анализ</button><button class="btn compact danger" data-action="remove-relation" data-id="${r.id}">Удалить связь</button></div>${r.classification_raw_response?`<details><summary>Ответ AI и диагностика</summary><pre class="raw">${esc(r.classification_raw_response)}${r.classification_error?'\n'+esc(r.classification_error):''}</pre></details>`:''}</article>`).join('')}</div>`;
}
function renderCandidates(items) {
  if(!items.length)return empty('Нет предложенных связей','CareerAI проверяет новые уточнения. Спорные совпадения появятся здесь.');
  return `<div class="review-list">${items.map(c=>`<article class="review-card"><div class="review-card-header"><h3>Уточнение #${c.sourcePostId} → публикация #${c.targetPostId}</h3>${badge(c.status)}</div><div class="review-texts"><div><small>УТОЧНЕНИЕ</small>${esc(c.sourceText)}</div><div><small>ОСНОВНАЯ ПУБЛИКАЦИЯ</small>${esc(c.targetText)}</div></div><p class="review-reason">${esc(c.reason||c.selectionReason)}</p><span class="muted">${esc(labels[c.proposedType]||'Тип ещё не определён')}${c.confidence!=null?' · Уверенность '+Math.round(c.confidence*100)+'%':''} · Попыток: ${c.attemptCount}</span>${c.error?`<p class="error-inline">${esc(c.error)}</p>`:''}<div class="actions">${['PENDING','REVIEW_REQUIRED','FAILED'].includes(c.status)?`<button class="btn primary compact" data-action="approve-candidate" data-id="${c.id}">Подтвердить</button>`:''}${['PENDING','REVIEW_REQUIRED','FAILED','AUTO_APPROVED'].includes(c.status)?`<button class="btn compact" data-action="reject-candidate" data-id="${c.id}">Отклонить</button>`:''}${['REVIEW_REQUIRED','FAILED','REJECTED','NO_RELATION'].includes(c.status)&&c.attemptCount<3?`<button class="btn compact" data-action="retry-candidate" data-id="${c.id}">Повторить анализ</button>`:''}<button class="btn compact" data-action="candidate-audit" data-id="${c.id}">История решения</button></div></article>`).join('')}</div>`;
}
function renderJobs(items) {
  return `<div class="job-list"><div class="notice">Операции выполняются небольшими порциями. Обновление индекса может обращаться к AI-провайдеру. Статусы операций хранятся до перезапуска приложения; история действий остаётся в журнале.</div><div class="actions"><button class="btn primary" data-action="job-embeddings">Обновить порцию индекса</button><button class="btn" data-action="job-candidates">Обработать порцию связей</button></div>${items.length?items.map(j=>`<div class="job-item"><div><strong>${esc({'embedding-batch':'Обновление индекса','candidate-batch':'Анализ связей','post-reindex':'Индексация публикации','post-discover':'Поиск связей'}[j.action]||j.action)}${j.entityId?' #'+j.entityId:''}</strong><small>${esc(j.message)} · ${esc(date(j.updatedAt))}</small></div>${badge(j.status)}</div>`).join(''):empty('Операций пока нет','Запущенные операции появятся в этом списке.')}</div>`;
}
function renderAudit(items) {
  if(!items.length)return empty('История пока пуста','Здесь будут записаны административные изменения.');
  return `<div class="table-wrap"><table><thead><tr><th>ВРЕМЯ</th><th>АДМИНИСТРАТОР</th><th>ДЕЙСТВИЕ</th><th>ОБЪЕКТ</th><th>ПРИЧИНА</th></tr></thead><tbody>${items.map(a=>`<tr><td>${esc(date(a.occurred_at))}</td><td>${esc(a.actor_telegram_id)}</td><td>${esc(a.action)}</td><td>${esc(a.entity_type)} ${a.entity_id?'#'+a.entity_id:''}</td><td>${esc(a.details)}</td></tr>`).join('')}</tbody></table></div>`;
}
function selectView(view) {
  state.view=view;state.page=0;const info=viewInfo[view];
  $('page-title').textContent=info[0];$('page-description').textContent=info[1];$('section-title').textContent=info[2];$('breadcrumb').textContent=info[0];
  document.querySelectorAll('[data-view]').forEach(el=>el.classList.toggle('active',el.dataset.view===view));
  $('filters').hidden=!['posts','faqs'].includes(view);
  for(const id of ['status-filter','type-filter'])$(id).hidden=view!=='posts';
  document.querySelectorAll('.date-field').forEach(el=>el.hidden=view!=='posts');
  $('search').placeholder=view==='faqs'?'Найти вопрос или ответ…':'Найти публикацию…';$('search').value='';
  $('view-actions').innerHTML=view==='faqs'?'<button class="btn primary compact" data-action="new-faq">+ Добавить ответ</button>':view==='relations'?'<button class="btn compact" data-action="new-relation">+ Связать публикации</button>':'';
  if(state.ready)load();
}
function showDialog(title,html,eyebrow='CAREERAI') {$('dialog-title').textContent=title;$('dialog-eyebrow').textContent=eyebrow;$('dialog-content').innerHTML=html;if(!$('detail-dialog').open)$('detail-dialog').showModal();}
function input(name,label,value='',opts={}) {return `<label class="${opts.wide?'wide':''}">${esc(label)}${opts.textarea?`<textarea name="${name}" ${opts.required?'required':''} maxlength="${opts.max||20000}">${esc(value)}</textarea>`:`<input name="${name}" value="${esc(value)}" type="${opts.type||'text'}" ${opts.required?'required':''} maxlength="${opts.max||2000}" ${opts.type==='number'?'min="0" max="100000"':''}>`}</label>`;}
const buttons = (text='Сохранить') => `<div class="actions"><button class="btn primary" type="submit">${esc(text)}</button><button class="btn" type="button" data-action="close">Отмена</button></div>`;
function typeSelect(name,selected='UPDATE') {return `<label>Тип изменения<select name="${name}">${['UPDATE','CORRECTION','CANCELLATION','MIXED'].map(t=>`<option value="${t}" ${t===selected?'selected':''}>${labels[t]}</option>`).join('')}</select></label>`;}
async function openPost(id) {
  const p=await api('/posts/'+id);state.post=p;
  const m=p.metadata;
  const link=p.channel_username&&/^[A-Za-z0-9_]+$/.test(p.channel_username)?`https://t.me/${p.channel_username}/${p.telegram_message_id}`:null;
  showDialog(m?.title||'Публикация #'+p.id,`<div class="detail-meta">${badge(postStatus(p))}<span>${esc(p.channel_title)} · ${esc(date(p.posted_at))}</span></div><div class="post-body">${esc(p.text)}</div><p class="muted">${esc(p.freshness_reason||'Срок публикации ещё не определён')}${p.expires_at?' · Истекает '+esc(date(p.expires_at)):''}</p><div class="actions">${link?`<a class="btn" href="${link}" target="_blank" rel="noopener noreferrer">Открыть в Telegram ↗</a>`:''}<button class="btn ${p.is_archived?'primary':'danger'}" data-action="${p.is_archived?'restore-post':'archive-post'}">${p.is_archived?'Восстановить':'В архив'}</button><button class="btn" data-action="freshness-post">Проверить срок</button></div>${m?`<h3 class="actions">Данные публикации</h3><div class="post-body">Категория: ${esc(labels[m.post_type])}\nКомпания: ${esc(m.company||'—')}\nДедлайн: ${esc(m.deadline_text||'—')}\nФормат: ${esc(m.format_text||'—')}\n${esc(m.summary||'')}</div><div class="actions"><button class="btn" data-action="edit-metadata">Уточнить данные</button><button class="btn" data-action="extract-post">Повторить извлечение</button></div>`:''}<details><summary>Обслуживание публикации</summary><div class="actions"><button class="btn compact" data-action="reindex-post">Обновить поисковый индекс</button><button class="btn compact" data-action="discover-post">Найти связанные публикации</button></div></details>${p.relations?.length?`<h3 class="actions">Связанные публикации</h3>${p.relations.map(r=>`<p class="review-reason">#${r.source_post_id} → #${r.target_post_id}: ${esc(labels[r.relation_type])}. ${esc(r.reason)}</p>`).join('')}`:''}`,`ПУБЛИКАЦИЯ #${p.id}`);
}
function editFaq(id) {
  const f=state.items.find(x=>x.id===id)||{};
  showDialog(id?'Изменить ответ':'Новый ответ',`<form class="form-grid" data-form="faq" data-id="${id||''}" data-revision="${f.revision||0}">${input('category','Категория',f.category||'general',{required:true,max:100})}${input('slug','Короткое имя (латиница и дефисы)',f.slug||'',{required:true,max:150})}${input('question','Вопрос',f.question||'',{required:true,wide:true})}${input('shortAnswer','Короткий ответ для /faq',f.short_answer||'',{required:true,wide:true,textarea:true,max:4000})}${input('fullAnswer','Полный проверенный ответ',f.full_answer||'',{required:true,wide:true,textarea:true})}${input('keywords','Ключевые слова',f.keywords||'')}${input('priority','Порядок показа',f.priority??100,{type:'number'})}<label class="wide"><input type="checkbox" name="active" ${f.is_active!==false?'checked':''}> Ответ доступен студентам</label>${buttons()}</form>`,'БАЗА ЗНАНИЙ');
}
function editMetadata() {
  const m=state.post.metadata;
  showDialog('Уточнить данные публикации',`<form class="form-grid" data-form="metadata"><label>Категория<select name="postType">${['VACANCY','EVENT','PRACTICE','DEADLINE','ANNOUNCEMENT','OTHER'].map(t=>`<option value="${t}" ${t===m.post_type?'selected':''}>${labels[t]}</option>`).join('')}</select></label>${input('title','Название',m.title,{max:500})}${input('company','Компания',m.company,{max:500})}${input('technologies','Технологии',m.technologies)}${input('levelText','Уровень',m.level_text,{max:255})}${input('formatText','Формат',m.format_text,{max:255})}${input('deadlineText','Дедлайн дословно, включая «до»',m.deadline_text,{max:500})}${input('practiceStartText','Начало практики',m.practice_start_text,{max:255})}${input('practiceEndText','Конец практики',m.practice_end_text,{max:255})}${input('summary','Краткое содержание',m.summary,{wide:true,textarea:true,max:10000})}<label class="wide"><input type="checkbox" name="relevantForPractice" ${m.is_relevant_for_practice?'checked':''}> Подходит для практики</label>${input('reason','Причина изменения','',{required:true,wide:true})}${buttons()}</form>`,'ПРОВЕРЕННЫЕ ДАННЫЕ');
}
function decision(kind,id) {
  const item=state.items.find(x=>x.id===id)||{};
  const confirm=kind.includes('confirm')||kind.includes('approve');
  const title={'confirm-relation':'Подтвердить связь','remove-relation':'Удалить связь','approve-candidate':'Подтвердить предложенную связь','reject-candidate':'Отклонить предложенную связь','archive-post':'Архивировать публикацию'}[kind];
  showDialog(title,`<form class="form-grid" data-form="decision" data-kind="${kind}" data-id="${id||''}" data-revision="${item.entity_version||0}">${confirm?typeSelect('type',item.proposedType||item.proposed_relation_type||item.relation_type||'UPDATE'):''}${input('reason','Причина решения',confirm?(item.proposed_reason||item.reason||''):'',{required:true,wide:true,textarea:true,max:2000})}${buttons(confirm?'Подтвердить':'Сохранить решение')}</form>`,'ЖУРНАЛИРУЕМОЕ ДЕЙСТВИЕ');
}
async function action(name,id) {
  if(name==='close'){$('detail-dialog').close();return;}
  if(name==='view-post')return openPost(id);
  if(name==='new-faq'||name==='edit-faq')return editFaq(id);
  if(name==='edit-metadata')return editMetadata();
  if(['confirm-relation','remove-relation','approve-candidate','reject-candidate','archive-post'].includes(name))return decision(name,id);
  if(name==='new-relation'){showDialog('Связать две публикации',`<form class="form-grid" data-form="link">${input('sourcePostId','ID уточнения (новая публикация)','',{required:true,type:'number'})}${input('targetPostId','ID основной публикации','',{required:true,type:'number'})}${typeSelect('type')}${input('reason','Что изменилось','',{required:true,wide:true,textarea:true})}${buttons('Создать связь')}</form>`);return;}
  if(name==='candidate-audit'){const entries=await api('/candidates/'+id+'/audit');showDialog('История решения',`<pre class="raw">${esc(JSON.stringify(entries,null,2))}</pre>`);return;}
  if(name==='retry-candidate'){await post('/candidates/'+id+'/retry');toast('Повторный анализ поставлен в очередь');return load();}
  if(name==='retry-relation'){const r=state.items.find(x=>x.id===id);await post('/relations/'+id+'/retry',{revision:r.entity_version});toast('Повторный анализ поставлен в очередь');return load();}
  if(name==='job-embeddings'||name==='job-candidates'){await post('/jobs/'+(name==='job-embeddings'?'embeddings':'candidates'));toast('Операция поставлена в очередь');return load();}
  if(name.endsWith('-post')) {
    const operation=name.replace('-post','');const p=state.post;
    if(['restore','freshness','extract'].includes(operation))await post(`/posts/${p.id}/${operation}`,{revision:p.revision});
    else await post(`/posts/${p.id}/${operation}`);
    toast(operation==='extract'?'Извлечение поставлено в очередь':'Действие принято');await openPost(p.id);await refresh();
  }
}
document.addEventListener('click',async event=>{
  const nav=event.target.closest('[data-view]');if(nav){selectView(nav.dataset.view);return;}
  const button=event.target.closest('[data-action]');if(!button)return;
  button.disabled=true;try{await action(button.dataset.action,Number(button.dataset.id)||null);}catch(e){toast(e.message);}finally{button.disabled=false;}
});
$('dialog-content').addEventListener('submit',async event=>{
  event.preventDefault();const form=event.target;const data=Object.fromEntries(new FormData(form));const submit=form.querySelector('[type=submit]');submit.disabled=true;
  try {
    if(form.dataset.form==='faq') {
      data.priority=Number(data.priority);data.active=data.active==='on';data.revision=Number(form.dataset.revision);
      const id=form.dataset.id;await api('/faqs'+(id?'/'+id:''),{method:id?'PUT':'POST',body:JSON.stringify(data)});
    }else if(form.dataset.form==='metadata') {
      data.revision=state.post.metadata.revision;data.relevantForPractice=data.relevantForPractice==='on';
      await api('/posts/'+state.post.id+'/metadata',{method:'PUT',body:JSON.stringify(data)});
    }else if(form.dataset.form==='link') {
      data.sourcePostId=Number(data.sourcePostId);data.targetPostId=Number(data.targetPostId);await post('/relations',data);
    }else {
      const kind=form.dataset.kind,id=form.dataset.id;data.revision=Number(form.dataset.revision);
      if(kind==='archive-post')await post('/posts/'+state.post.id+'/archive',{reason:data.reason,revision:state.post.revision});
      else if(kind==='confirm-relation')await post('/relations/'+id+'/confirm',data);
      else if(kind==='remove-relation')await post('/relations/'+id+'/remove',data);
      else await post('/candidates/'+id+'/'+(kind==='approve-candidate'?'approve':'reject'),data);
    }
    $('detail-dialog').close();toast('Изменения сохранены');await refresh();
  }catch(e){toast(e.message);}finally{submit.disabled=false;}
});
$('close-dialog').addEventListener('click',()=>$('detail-dialog').close());
$('refresh').addEventListener('click',refresh);$('retry-auth').addEventListener('click',start);
$('filters').addEventListener('submit',event=>{event.preventDefault();state.page=0;load();});
for(const id of ['status-filter','type-filter'])$(id).addEventListener('change',()=>{state.page=0;load();});
$('prev').addEventListener('click',()=>{if(state.page>0){state.page--;load();}});
$('next').addEventListener('click',()=>{state.page++;load();});
setInterval(()=>{if(state.ready&&state.view==='jobs'&&!document.hidden)load();},5000);
start();
