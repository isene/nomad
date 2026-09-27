package com.isene.gaze

/**
 * The script put into every page: it notices a login being sent, and
 * fills one in. The laptop's gaze runs the same code. Pages talk back
 * through `gazeBridge.post` with one JSON object per message.
 */
const val PAGE_SCRIPT = """
(function () {
  if (window.__gaze) return;
  const post = m => { try { gazeBridge.post(JSON.stringify(m)); } catch (e) {} };
  const visible = el => {
    if (!el) return false;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    const cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none';
  };
  const setValue = (el, v) => {
    if (!el || v == null) return;
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    const d = Object.getOwnPropertyDescriptor(proto, 'value');
    if (d && d.set) d.set.call(el, v); else el.value = v;
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  };
  const userField = pw => {
    const scope = pw.form || document;
    const inputs = [...scope.querySelectorAll('input')];
    const i = inputs.indexOf(pw);
    for (let k = i - 1; k >= 0; k--) {
      const t = (inputs[k].type || 'text').toLowerCase();
      if (['text', 'email', 'tel'].includes(t) && visible(inputs[k])) return inputs[k];
    }
    return null;
  };
  const passwordFields = () => [...document.querySelectorAll('input[type=password]')].filter(visible);

  window.__gaze = {
    hasPasswordField() { return passwordFields().length > 0; },
    fill(u, p) {
      const pws = passwordFields();
      if (pws.length) {
        setValue(userField(pws[0]), u);
        setValue(pws[0], p);
        return 'both';
      }
      const uf = [...document.querySelectorAll(
        'input[type=email], input[autocomplete=username], input[name*=user i], input[name*=email i], input[id*=user i], input[id*=email i], input[type=text]')]
        .find(visible);
      if (uf) { setValue(uf, u); return 'user'; }
      return 'none';
    },
  };

  const remember = () => {
    const pws = passwordFields().filter(p => p.value);
    if (!pws.length) return;
    const u = userField(pws[0]);
    post({ t: 'login', username: u ? u.value : '', password: pws[0].value });
  };
  document.addEventListener('submit', remember, true);
  document.addEventListener('keydown', e => { if (e.key === 'Enter' && e.target && e.target.type === 'password') remember(); }, true);
  document.addEventListener('click', e => {
    const b = e.target && e.target.closest && e.target.closest('button, input[type=submit], [role=button]');
    if (b) remember();
  }, true);
})();
"""
