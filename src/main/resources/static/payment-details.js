// Render payment data as text, never as HTML supplied by a payment file.
const paymentDialog = document.getElementById('paymentDialog');
document.getElementById('closePaymentDialog').onclick = () => paymentDialog.close();
let activePaymentId = null;
let paymentDetailBusy = false;
let veuPublishBusy = false;
const veuOriginators = new Map();
const eventLabels = {CREATED:'Angelegt / geprüft',SIGNED:'Unterschrift',SCHEDULED:'Terminiert',BOOKED:'Belastung gebucht',CREDIT_BOOKED:'Gutschrift gebucht',BOOKING_FAILED:'Buchung fehlgeschlagen',FILE_UPLOAD:'Upload empfangen',VEU_FORWARDING:'Weitere Freigabe nötig',ORDER_HAC_FINAL_POS:'EBICS-Auftrag abgeschlossen',REPORT_PROVIDED:'Bericht bereitgestellt',REPORT_RECEIVED:'Empfang bestätigt',REPORT_REJECTED:'Empfang fehlgeschlagen'};
async function renderPaymentDetails(id) {
  eventLabels.VEU_PUBLISHED='Für VEU bereitgestellt';
  eventLabels.VEU_SIGNED='VEU-Unterschrift geprüft';
  if (paymentDetailBusy) return;
  paymentDetailBusy = true;
  try {
    const detail = await apiJson(`/admin/api/payment-orders/${id}`);
    if (activePaymentId !== id) return;
    const order = detail.order;
    document.getElementById('paymentDialogTitle').textContent = `Zahlung #${id}`;
    const content = document.getElementById('paymentDialogContent');
    content.replaceChildren();
    const data = document.createElement('dl');
    const fields = {'Quelle':detail.source,'Status':order.status,'Betrag':money(order.amount,order.currency),'Belastungskonto':order.iban,'Empfänger':order.beneficiaryName,'Empfängerkonto':order.beneficiaryIban,'Ausführung':order.executionDate,'Verwendungszweck':order.purpose,'EBICS-Auftragsnummer':order.ebicsOrderId,'Original MsgId':order.originalMessageId,'Original PmtInfId':order.originalPaymentInformationId,'Original InstrId':order.originalInstructionId,'End-to-End-ID':order.endToEndId,'Belastungsreferenz':order.ledgerTransactionId};
    for (const [label,value] of Object.entries(fields)) {
      const term = document.createElement('dt'); term.textContent = label; term.className = 'subtle';
      const description = document.createElement('dd'); description.textContent = value ?? '—'; description.style.margin = '0 0 10px';
      data.append(term,description);
    }
    content.append(data);
    if(order.status==='PENDING_SIGNATURES'&&!order.submissionId&&!(detail.signatures??[]).length){
      const [participants,accounts]=await Promise.all([apiJson('/admin/api/participants'),apiJson('/admin/api/accounts')]);
      if(activePaymentId!==id)return;
      const account=accounts.find(a=>a.iban===order.iban);
      const label=document.createElement('label');label.textContent='VEU-Einreicher (keine Unterschrift)';
      const select=document.createElement('select');
      for(const p of participants.filter(p=>p.active&&p.partnerId===account?.partnerId)){
        const option=document.createElement('option');option.value=p.userId;option.textContent=p.userId;select.append(option);
      }
      if(veuOriginators.has(id))select.value=veuOriginators.get(id);
      select.onchange=()=>veuOriginators.set(id,select.value);label.append(select);content.append(label);
      const button=document.createElement('button');button.textContent='Für VEU in Subsembly bereitstellen';button.disabled=!select.options.length;
      const result=document.createElement('p');
      button.onclick=async()=>{
        button.disabled=true;veuPublishBusy=true;
        try{
          const response=await fetch(`/admin/api/veu/${id}`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({originator:select.value})});
          const data=await response.json();if(!response.ok)throw new Error(data.message??'Bereitstellung fehlgeschlagen');
          result.textContent=`${data.message} EBICS-Auftrag: ${data.orderId}`;
          await renderPaymentDetails(id);
        }catch(error){result.textContent=error.message;button.disabled=false;}finally{veuPublishBusy=false;}
      };
      content.append(button,result);
    }
    const heading=document.createElement('h3');heading.textContent='Ereignisverlauf';content.append(heading);
    const list=document.createElement('ol');
    for(const event of detail.events ?? []) {
      const item=document.createElement('li');item.style.marginBottom='12px';
      item.textContent=`${logTime(event.occurredAt)} · ${eventLabels[event.kind] ?? event.kind}: ${event.detail}`;
      list.append(item);
    }
    if(!list.children.length){const note=document.createElement('p');note.textContent='Altauftrag: Für diesen Auftrag wurden noch keine Ereignisse aufgezeichnet.';content.append(note);}
    else content.append(list);
    const note=document.createElement('p');note.textContent='Eine EBICS-Empfangsbestätigung bestätigt den Transport, nicht den fachlichen Import im Banking-Client.';content.append(note);
  } finally { paymentDetailBusy=false; }
}
window.showPaymentDetails = async function(id) {
  activePaymentId=id;
  if(!paymentDialog.open)paymentDialog.showModal();
  try {await renderPaymentDetails(id);} catch(error){document.getElementById('paymentDialogContent').textContent=error.message;}
};
setInterval(()=>{if(paymentDialog.open&&!veuPublishBusy&&document.getElementById('liveRefresh').checked)renderPaymentDetails(activePaymentId).catch(console.error);},5000);
apiJson('/admin/api/customers').then(customers=>{
  const select=document.getElementById('scenarioPartner');
  for(const customer of customers){const option=document.createElement('option');option.value=customer.partnerId;option.textContent=`${customer.partnerId} · ${customer.name}`;select.append(option);}
}).catch(console.error);
document.getElementById('scenarioForm').addEventListener('submit',async event=>{
  event.preventDefault();const button=document.getElementById('scenarioSubmit'),result=document.getElementById('scenarioResult');button.disabled=true;
  try{
    const response=await fetch('/admin/api/test-scenarios',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({partnerId:document.getElementById('scenarioPartner').value,scenario:document.getElementById('scenarioType').value})});
    if(!response.ok)throw new Error(response.status===403?'Nur Administratoren dürfen Szenarien anlegen.':await response.text());
    const data=await response.json();result.textContent=`${data.message}\nKonten: ${data.ibans.join(', ')}\nAufträge: ${data.paymentIds.join(', ')}`;await load();
  }catch(error){result.textContent=`Nicht angelegt: ${error.message}`;}finally{button.disabled=false;}
});
