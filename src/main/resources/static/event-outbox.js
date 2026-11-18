(() => {
  const el = id => document.getElementById(id);
  let busy = false;
  const date = value => value ? new Date(value).toLocaleString('de-DE', {timeZone:'Europe/Berlin'}) : '—';
  async function request(url, options) {
    const response = await fetch(url, options);
    if (response.redirected) throw new Error('Sitzung abgelaufen. Bitte erneut anmelden.');
    if (response.status === 403) throw new Error('Diese Ansicht benötigt Administratorrechte.');
    if (response.status === 409) throw new Error('Das Ereignis ist nicht mehr im Status DEAD. Bitte aktualisieren.');
    if (!response.ok) throw new Error('Anfrage fehlgeschlagen (' + response.status + ').');
    return response.json();
  }
  async function load() {
    if (busy) return;
    busy = true;
    try {
      const [summary, entries] = await Promise.all([request('/admin/api/events/status'), request('/admin/api/events?status=' + el('outboxFilter').value)]);
      el('outboxSummary').textContent = `Ausstehend: ${summary.pending} · Zugestellt: ${summary.delivered} · Fehlgeschlagen: ${summary.dead} · Versand: ${summary.dispatchEnabled ? 'aktiv' : 'deaktiviert'} · Adapter: ${summary.adapterConfigured ? 'vorhanden' : 'nicht konfiguriert'}`;
      el('outboxRows').replaceChildren();
      for (const entry of entries) {
        const row = document.createElement('tr');
        for (const value of [entry.id, entry.status, entry.attempts, date(entry.nextAttemptAt), date(entry.deliveredAt), entry.lastError || '—']) {
          const cell = document.createElement('td'); cell.textContent = value; row.append(cell);
        }
        const action = document.createElement('td');
        if (entry.status === 'DEAD') {
          const button = document.createElement('button'); button.type = 'button'; button.textContent = 'Erneut einplanen';
          button.addEventListener('click', async () => {
            button.disabled = true;
            try {
              await request('/admin/api/events/' + encodeURIComponent(entry.id) + '/replay', {method:'POST'});
              el('outboxMessage').textContent = 'Ereignis erneut eingeplant. Die Zustellung erfolgt bei aktiviertem Versand.';
              await load();
            } catch (error) { el('outboxMessage').textContent = error.message; button.disabled = false; }
          });
          action.append(button);
        }
        row.append(action); el('outboxRows').append(row);
      }
      if (!entries.length) el('outboxMessage').textContent = 'Keine Ereignisse für diesen Status.';
    } catch (error) { el('outboxMessage').textContent = error.message; el('outboxRows').replaceChildren(); }
    finally { busy = false; }
  }
  el('outboxFilter').addEventListener('change', () => { el('outboxMessage').textContent = ''; load(); });
  el('outboxRefresh').addEventListener('click', load);
  document.querySelector('#menu [data-view="eventOutbox"]').addEventListener('click', load);
  if (location.hash === '#eventOutbox') load();
  setInterval(() => {
    if (document.querySelector('.view[data-view="eventOutbox"]').classList.contains('active') && el('liveRefresh').checked) load();
  }, 5000);
})();
