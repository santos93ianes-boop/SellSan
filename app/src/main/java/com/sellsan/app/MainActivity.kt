package com.sellsan.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private val prefs by lazy { getSharedPreferences("sellsan", MODE_PRIVATE) }
    private val brl = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
    private val gold = Color.rgb(212, 175, 55)
    private val dark = Color.rgb(14, 15, 18)
    private val panel = Color.rgb(28, 30, 35)
    private val muted = Color.rgb(176, 180, 190)
    private val statuses = listOf("Novo","Conversando","Orçamento","Follow-up","Fechado","Perdido")
    private var voiceTarget: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        showShell("dashboard")
    }

    private fun showShell(screen: String) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(dark) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,16); setBackgroundColor(Color.rgb(18,19,22)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text="SELLSAN"; textSize=24f; setTextColor(gold); setTypeface(typeface,1) }, LinearLayout.LayoutParams(0,-2,1f))
        top.addView(TextView(this).apply { text=if(isLogged()) "● NUVEM" else "○ LOCAL"; textSize=10f; setTextColor(if(isLogged()) gold else muted) })
        header.addView(top)
        header.addView(TextView(this).apply { text="Seu assistente inteligente de vendas e serviços"; textSize=10f; setTextColor(muted) })
        root.addView(header)

        content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(22,22,22,28) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1,0,1f))

        val nav = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false }
        val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(8,8,8,8); setBackgroundColor(Color.rgb(18,19,22)) }
        listOf("Início" to "dashboard","Clientes" to "clients","Funil" to "funnel","Orç." to "quotes","Agenda" to "agenda","IA" to "ai","Resultados" to "results","Conta" to "settings").forEach { (label,id) ->
            row.addView(navButton(label, screen==id) { showShell(id) }, LinearLayout.LayoutParams(170,-2))
        }
        nav.addView(row); root.addView(nav); setContentView(root)
        when(screen){"clients"->clientsScreen();"funnel"->funnelScreen();"quotes"->quotesScreen();"agenda"->agendaScreen();"ai"->aiScreen();"results"->resultsScreen();"settings"->settingsScreen();else->dashboardScreen()}
    }

    private fun dashboardScreen(){
        title("Painel inteligente","Veja o que já vendeu, o que pode vender e o que precisa fazer agora.")
        val clients=getArray("clients"); val quotes=getArray("quotes"); var open=0;var potential=0.0;var won=0.0;var follow=0
        for(i in 0 until quotes.length()){val q=quotes.getJSONObject(i); when(q.optString("status","Novo")){"Fechado"->won+=q.optDouble("value");"Perdido"->{};else->{open++;potential+=q.optDouble("value");if(q.optString("status")=="Follow-up")follow++}}}
        card("Vendas fechadas",brl.format(won),"Receita marcada como fechada")
        card("Potencial em aberto",brl.format(potential),"$open oportunidade(s) ativas")
        card("Clientes",clients.length().toString(),"$follow follow-up(s) pendente(s)")
        section("Ações rápidas")
        content.addView(button("+ Novo cliente"){showShell("clients")});content.addView(button("+ Criar orçamento"){showShell("quotes")});content.addView(button("✦ Perguntar ao SellSan IA"){showShell("ai")})
        if(isLogged()) content.addView(secondary("↻ Sincronizar agora"){ syncNow(true) })
        section("O que fazer agora")
        val hints=mutableListOf<String>(); if(open>0)hints.add("Você tem $open orçamento(s) em aberto. Revise o funil e faça follow-up.");if(follow>0)hints.add("$follow cliente(s) já estão no estágio Follow-up.");if(clients.length()==0)hints.add("Cadastre o primeiro cliente para iniciar seu funil comercial.");if(hints.isEmpty())hints.add("Sem pendências críticas. Continue alimentando novas oportunidades.")
        hints.forEach{content.addView(txt("• $it",14,false),margin())}
    }

    private fun clientsScreen(){
        title("Clientes","Cadastre contatos e mantenha todo o histórico comercial organizado.")
        val name=input("Nome do cliente");val phone=input("WhatsApp com DDD");val need=input("Serviço / necessidade");content.addView(name);content.addView(phone);content.addView(need)
        content.addView(button("Salvar cliente") {
            if(name.text.isBlank())return@button toast("Informe o nome")
            val a=getArray("clients");a.put(JSONObject().put("id",UUID.randomUUID().toString()).put("name",name.text.toString()).put("phone",phone.text.toString()).put("need",need.text.toString()).put("created",now()));saveArray("clients",a);autoSync();showShell("clients")
        })
        section("Clientes salvos"); val a=getArray("clients");if(a.length()==0)empty("Nenhum cliente cadastrado.")
        for(i in a.length()-1 downTo 0){val c=a.getJSONObject(i);val box=box();box.addView(txt(c.optString("name"),16,true));box.addView(txt(c.optString("phone")+"\n"+c.optString("need"),13,false));box.addView(secondary("Abrir WhatsApp"){openWhatsApp(c.optString("phone"),"Olá ${c.optString("name")}! Tudo bem? Estou entrando em contato sobre ${c.optString("need")}.")});content.addView(box,margin())}
    }

    private fun quotesScreen(){
        title("Orçamentos","Crie, envie, gere PDF e acompanhe a oportunidade até o fechamento.")
        val client=input("Cliente");val phone=input("WhatsApp");val service=input("Serviço / produto");val value=input("Valor (ex.: 850,00)");val details=input("Condições / observações")
        listOf(client,phone,service,value,details).forEach{content.addView(it)}
        content.addView(button("Salvar orçamento") {
            val v=parseMoney(value.text.toString());if(client.text.isBlank()||service.text.isBlank())return@button toast("Preencha cliente e serviço")
            val a=getArray("quotes");a.put(JSONObject().put("id",UUID.randomUUID().toString()).put("client",client.text.toString()).put("phone",phone.text.toString()).put("service",service.text.toString()).put("value",v).put("details",details.text.toString()).put("status","Novo").put("created",now()));saveArray("quotes",a);autoSync();showShell("quotes")
        })
        content.addView(secondary("Enviar prévia pelo WhatsApp") {openWhatsApp(phone.text.toString(),quoteMessage(client.text.toString(),service.text.toString(),parseMoney(value.text.toString()),details.text.toString()))})
        section("Orçamentos")
        val a=getArray("quotes");if(a.length()==0)empty("Nenhum orçamento criado.")
        for(i in a.length()-1 downTo 0){val q=a.getJSONObject(i);val box=box();box.addView(txt("${q.optString("client")} • ${brl.format(q.optDouble("value"))}",16,true));box.addView(txt("${q.optString("service")}\n${q.optString("status")}",12,false))
            val sp=Spinner(this);sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,statuses);sp.setSelection(statuses.indexOf(q.optString("status","Novo")).coerceAtLeast(0));sp.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(parent:AdapterView<*>?){};override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long){val ns=statuses[position];if(q.optString("status","Novo")!=ns){q.put("status",ns);saveArray("quotes",a);autoSync()}}};box.addView(sp)
            box.addView(secondary("WhatsApp"){openWhatsApp(q.optString("phone"),quoteMessage(q.optString("client"),q.optString("service"),q.optDouble("value"),q.optString("details")))})
            box.addView(secondary("Gerar PDF e compartilhar"){shareQuotePdf(q)})
            box.addView(secondary("Lembrar amanhã"){scheduleFollowUp(q.optString("client"),"Retorne o orçamento de ${brl.format(q.optDouble("value"))} para ${q.optString("client")}.")})
            content.addView(box,margin())
        }
    }

    private fun funnelScreen(){
        title("Funil de vendas","Acompanhe cada oportunidade desde o primeiro contato até o fechamento.")
        val a=getArray("quotes")
        statuses.forEach{st->val list=mutableListOf<Int>();var total=0.0;for(i in 0 until a.length()){val q=a.getJSONObject(i);if(q.optString("status","Novo")==st){list.add(i);total+=q.optDouble("value")}};section("$st • ${list.size} • ${brl.format(total)}");if(list.isEmpty())empty("Sem oportunidades aqui.");list.forEach{idx->val q=a.getJSONObject(idx);val box=box();box.addView(txt("${q.optString("client")} — ${brl.format(q.optDouble("value"))}",15,true));box.addView(txt(q.optString("service"),12,false));val sp=Spinner(this);sp.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,statuses);sp.setSelection(statuses.indexOf(st));sp.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(parent:AdapterView<*>?){};override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long){val ns=statuses[position];if(q.optString("status","Novo")!=ns){q.put("status",ns);saveArray("quotes",a);autoSync();toast("Movido para $ns")}}};box.addView(sp);content.addView(box,margin())}}
    }

    private fun agendaScreen(){
        title("Agenda","Converta vendas em atendimentos e mantenha o próximo passo sob controle.")
        val client=input("Cliente");val date=input("Data e hora (ex.: 15/09 14:00)");val service=input("Serviço");content.addView(client);content.addView(date);content.addView(service)
        content.addView(button("Agendar") { if(client.text.isBlank()||date.text.isBlank())return@button toast("Informe cliente e data");val a=getArray("agenda");a.put(JSONObject().put("id",UUID.randomUUID().toString()).put("client",client.text.toString()).put("date",date.text.toString()).put("service",service.text.toString()).put("done",false));saveArray("agenda",a);autoSync();showShell("agenda") })
        section("Próximos atendimentos");val a=getArray("agenda");if(a.length()==0)empty("Nenhum atendimento agendado.");for(i in 0 until a.length()){val x=a.getJSONObject(i);val box=box();box.addView(txt("${x.optString("date")} • ${x.optString("client")}",15,true));box.addView(txt(x.optString("service"),12,false));content.addView(box,margin())}
    }

    private fun aiScreen(){
        title("SellSan IA", if(isLogged()) "IA conectada ao seu servidor SellSan e ao contexto comercial da conta." else "Entre na sua conta em Conta para usar a IA real. O modo local continua disponível.")
        val prompt=input("Ex.: Quais clientes devo chamar hoje? Crie uma mensagem de follow-up para o orçamento do Carlos.");prompt.minLines=5;voiceTarget=prompt;content.addView(prompt)
        content.addView(secondary("🎙 Falar com SellSan"){startVoice(prompt)})
        val out=TextView(this).apply{text="A resposta aparecerá aqui.";textSize=15f;setTextColor(Color.WHITE);setPadding(18,18,18,18);setBackgroundColor(panel)}
        content.addView(button("✦ Analisar com SellSan IA") { val p=prompt.text.toString().trim();if(p.isBlank())return@button toast("Digite uma pergunta");out.text="Analisando...";callAi(p){answer->out.text=answer} })
        content.addView(secondary("Enviar resposta no WhatsApp"){openWhatsApp("",out.text.toString())});content.addView(out,margin())
        section("Privacidade");content.addView(txt("A chave da IA fica apenas no servidor. O aplicativo envia somente a pergunta e o contexto comercial necessário para responder.",12,false))
    }

    private fun resultsScreen(){
        title("Resultados","Indicadores fáceis de entender para acompanhar crescimento e conversão.")
        val a=getArray("quotes");var won=0.0;var lost=0.0;var open=0.0;var wonCount=0;var lostCount=0
        for(i in 0 until a.length()){val q=a.getJSONObject(i);when(q.optString("status","Novo")){"Fechado"->{won+=q.optDouble("value");wonCount++};"Perdido"->{lost+=q.optDouble("value");lostCount++};else->open+=q.optDouble("value")}}
        card("Faturamento registrado",brl.format(won),"$wonCount venda(s) fechada(s)");card("Potencial em negociação",brl.format(open));card("Valor perdido",brl.format(lost),"$lostCount oportunidade(s) perdida(s)")
        val total=wonCount+lostCount;val conversion=if(total>0)wonCount*100.0/total else 0.0;card("Conversão",String.format(Locale("pt","BR"),"%.1f%%",conversion),"Fechados ÷ (fechados + perdidos)")
    }

    private fun settingsScreen(){
        title("Conta e conectividade","Configure sua empresa, crie a conta SellSan e ative nuvem + IA.")
        val company=input("Nome da empresa").apply{setText(prefs.getString("company",""))};val seller=input("Seu nome").apply{setText(prefs.getString("seller",""))};val server=input("URL do servidor SellSan").apply{setText(prefs.getString("server_url",""))}
        content.addView(company);content.addView(seller);content.addView(server)
        content.addView(button("Salvar empresa e servidor"){prefs.edit().putString("company",company.text.toString()).putString("seller",seller.text.toString()).putString("server_url",server.text.toString().trim()).apply();toast("Configurações salvas")})
        section(if(isLogged()) "Conta conectada" else "Entrar ou criar conta")
        if(isLogged()){
            content.addView(txt("Conectado como ${prefs.getString("user_email","")}",14,true));content.addView(button("Sincronizar nuvem"){syncNow(true)});content.addView(secondary("Sair da conta"){prefs.edit().remove("auth_token").remove("user_email").apply();showShell("settings")})
        } else {
            val email=input("E-mail");val pass=input("Senha (mínimo 6 caracteres)");pass.inputType=0x00000081;content.addView(email);content.addView(pass)
            content.addView(button("Entrar"){login(email.text.toString(),pass.text.toString())})
            content.addView(secondary("Criar conta SellSan"){register(company.text.toString(),seller.text.toString(),email.text.toString(),pass.text.toString())})
        }
        section("Teste do servidor");content.addView(secondary("Verificar conexão"){checkServer()})
        content.addView(txt("O app funciona em modo local mesmo sem servidor. Para login, sincronização e IA real, publique o backend incluído neste projeto e informe a URL HTTPS acima.",12,false))
    }

    private fun login(email:String,password:String){
        if(email.isBlank()||password.isBlank())return toast("Informe e-mail e senha")
        val server=serverUrl();thread{val r=CloudClient.request(server,"/api/auth/login","POST",body=JSONObject().put("email",email).put("password",password));runOnUiThread{if(r.ok){val token=r.body?.optString("token").orEmpty();prefs.edit().putString("auth_token",token).putString("user_email",email).apply();toast("Conta conectada");syncNow(true)}else toast(r.message)}}
    }

    private fun register(company:String,name:String,email:String,password:String){
        if(company.isBlank()||name.isBlank()||email.isBlank()||password.length<6)return toast("Preencha empresa, nome, e-mail e senha com 6+ caracteres")
        val server=serverUrl();thread{val r=CloudClient.request(server,"/api/auth/register","POST",body=JSONObject().put("company",company).put("name",name).put("email",email).put("password",password));runOnUiThread{if(r.ok){prefs.edit().putString("auth_token",r.body?.optString("token")).putString("user_email",email).putString("company",company).putString("seller",name).apply();toast("Conta criada");syncNow(true)}else toast(r.message)}}
    }

    private fun checkServer(){ val server=serverUrl();thread{val r=CloudClient.request(server,"/api/health");runOnUiThread{toast(if(r.ok) "Servidor online" else "Falha: ${r.message}")}} }

    private fun syncNow(showToast:Boolean){
        if(!isLogged()) { if(showToast) toast("Entre na sua conta primeiro"); return }
        val server=serverUrl();val token=token();val local=snapshot()
        thread {
            val pull=CloudClient.request(server,"/api/sync","GET",token)
            if(!pull.ok){runOnUiThread{if(showToast)toast("Falha na sincronização: ${pull.message}")};return@thread}
            val merged=mergeSnapshots(pull.body ?: JSONObject(),local)
            val push=CloudClient.request(server,"/api/sync","PUT",token,merged)
            if(push.ok){applySnapshot(merged);runOnUiThread{if(showToast)toast("Sincronizado com a nuvem");showShell("dashboard")}}
            else runOnUiThread{if(showToast)toast("Falha na sincronização: ${push.message}")}
        }
    }

    private fun mergeSnapshots(remote:JSONObject, local:JSONObject):JSONObject{
        fun mergeArray(key:String):JSONArray{
            val map=linkedMapOf<String,JSONObject>()
            val r=remote.optJSONArray(key)?:JSONArray();for(i in 0 until r.length()){val o=r.optJSONObject(i)?:continue;map[o.optString("id",o.toString())]=o}
            val l=local.optJSONArray(key)?:JSONArray();for(i in 0 until l.length()){val o=l.optJSONObject(i)?:continue;map[o.optString("id",o.toString())]=o}
            return JSONArray().apply{map.values.forEach{put(it)}}
        }
        return JSONObject().put("clients",mergeArray("clients")).put("quotes",mergeArray("quotes")).put("agenda",mergeArray("agenda")).put("company",local.optString("company",remote.optString("company"))).put("seller",local.optString("seller",remote.optString("seller"))).put("updatedAt",System.currentTimeMillis())
    }

    private fun autoSync(){ if(isLogged()) thread{CloudClient.request(serverUrl(),"/api/sync","PUT",token(),snapshot())} }

    private fun callAi(prompt:String, done:(String)->Unit){
        if(!isLogged()){done(localReply(prompt)+"\n\nPara usar a IA real, conecte sua conta em Conta.");return}
        val body=JSONObject().put("prompt",prompt).put("businessContext",snapshot())
        thread { val r=CloudClient.request(serverUrl(),"/api/ai","POST",token(),body);val answer=if(r.ok)r.body?.optString("answer").orEmpty() else "Não foi possível acessar a IA: ${r.message}\n\n${localReply(prompt)}";Handler(Looper.getMainLooper()).post{done(answer)} }
    }

    private fun snapshot()=JSONObject().put("clients",getArray("clients")).put("quotes",getArray("quotes")).put("agenda",getArray("agenda")).put("company",prefs.getString("company","")).put("seller",prefs.getString("seller","")).put("updatedAt",System.currentTimeMillis())
    private fun applySnapshot(j:JSONObject){prefs.edit().putString("clients",j.optJSONArray("clients")?.toString()?:"[]").putString("quotes",j.optJSONArray("quotes")?.toString()?:"[]").putString("agenda",j.optJSONArray("agenda")?.toString()?:"[]").apply()}

    private fun shareQuotePdf(q:JSONObject){
        try{
            val pdf=PdfDocument();val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,1).create());val c=page.canvas
            val p=Paint().apply{color=Color.BLACK;textSize=18f;isFakeBoldText=true};c.drawText("SELLSAN • ORÇAMENTO",42f,60f,p)
            p.textSize=12f;p.isFakeBoldText=false
            val lines=listOf("Empresa: ${prefs.getString("company","SellSan")}","Cliente: ${q.optString("client")}","Data: ${q.optString("created")}","Serviço: ${q.optString("service")}","Valor: ${brl.format(q.optDouble("value"))}","Status: ${q.optString("status")}","Condições: ${q.optString("details").ifBlank{"A combinar"}}")
            var y=105f;lines.forEach{c.drawText(it.take(80),42f,y,p);y+=30f};p.textSize=10f;c.drawText("Gerado pelo SellSan",42f,790f,p);pdf.finishPage(page)
            val file=File(cacheDir,"orcamento_${q.optString("client").replace(" ","_")}.pdf");FileOutputStream(file).use{pdf.writeTo(it)};pdf.close()
            val uri=FileProvider.getUriForFile(this,"$packageName.fileprovider",file);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Compartilhar orçamento"))
        }catch(e:Exception){toast("Falha ao gerar PDF: ${e.message}")}
    }

    private fun scheduleFollowUp(client:String,text:String){
        val intent=Intent(this,ReminderReceiver::class.java).putExtra("client",client).putExtra("text",text)
        val pi=PendingIntent.getBroadcast(this,(System.currentTimeMillis()%Int.MAX_VALUE).toInt(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm=getSystemService(ALARM_SERVICE) as AlarmManager;alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+24*60*60*1000,pi);toast("Lembrete agendado para amanhã")
    }

    private fun requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33 && ActivityCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)ActivityCompat.requestPermissions(this,arrayOf(Manifest.permission.POST_NOTIFICATIONS),31)}
    private fun startVoice(target:EditText){voiceTarget=target;try{startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);putExtra(RecognizerIntent.EXTRA_LANGUAGE,"pt-BR")},900)}catch(_:Exception){toast("Reconhecimento de voz indisponível")}}
    @Deprecated("Deprecated in Java") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);if(requestCode==900&&resultCode==RESULT_OK){voiceTarget?.setText(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty())}}

    private fun localReply(p:String):String{val l=p.lowercase();val q=getArray("quotes");return when{l.contains("quem")&&l.contains("chamar")->{val names=mutableListOf<String>();for(i in 0 until q.length()){val x=q.getJSONObject(i);if(x.optString("status")!in listOf("Fechado","Perdido"))names.add(x.optString("client"))};if(names.isEmpty())"Você ainda não tem oportunidades abertas." else "Priorize hoje: ${names.take(5).joinToString(", ")}. Comece pelos orçamentos mais antigos e pelos clientes em Follow-up."};l.contains("follow")||l.contains("mensagem")->"Olá! Passando para saber se conseguiu avaliar o orçamento. Se tiver alguma dúvida ou quiser ajustar alguma condição, posso te ajudar. Posso reservar o atendimento para você?";else->"Posso ajudar com clientes, orçamentos, follow-up e próximos passos. Conecte sua conta para análises avançadas com IA."}}
    private fun quoteMessage(client:String,service:String,value:Double,details:String):String{val company=prefs.getString("company","SellSan")!!.ifBlank{"SellSan"};return "Olá $client! Segue o orçamento da $company:\n\n$service\nValor: ${brl.format(value)}\n${details.ifBlank{"Condições a combinar."}}\n\nSe estiver de acordo, me confirme para reservarmos o atendimento."}
    private fun openWhatsApp(phone:String,message:String){try{val clean=phone.filter{it.isDigit()}.removePrefix("55");val encoded=URLEncoder.encode(message,"UTF-8");val url=if(clean.isBlank())"https://wa.me/?text=$encoded" else "https://wa.me/55$clean?text=$encoded";startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}catch(_:Exception){toast("Não foi possível abrir o WhatsApp")}}
    private fun parseMoney(s:String)=s.replace(".","").replace(",",".").toDoubleOrNull()?:0.0
    private fun serverUrl()=prefs.getString("server_url","")!!.trim().trimEnd('/')
    private fun token()=prefs.getString("auth_token","")!!.trim()
    private fun isLogged()=token().isNotBlank() && serverUrl().isNotBlank()
    private fun now()=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("pt","BR")).format(Date())
    private fun getArray(key:String)=try{JSONArray(prefs.getString(key,"[]"))}catch(_:Exception){JSONArray()}
    private fun saveArray(key:String,arr:JSONArray){prefs.edit().putString(key,arr.toString()).apply()}
    private fun navButton(text:String,active:Boolean,action:()->Unit)=MaterialButton(this).apply{this.text=text;textSize=11f;setTextColor(if(active)dark else Color.WHITE);setBackgroundColor(if(active)gold else Color.rgb(40,42,47));setOnClickListener{action()}}
    private fun title(text:String,subtitle:String=""){content.addView(TextView(this).apply{this.text=text;textSize=27f;setTextColor(Color.WHITE);setTypeface(typeface,1)});if(subtitle.isNotBlank())content.addView(TextView(this).apply{this.text=subtitle;textSize=13f;setTextColor(muted);setPadding(0,4,0,18)})}
    private fun section(text:String){content.addView(TextView(this).apply{this.text=text;textSize=17f;setTextColor(gold);setTypeface(typeface,1);setPadding(0,18,0,10)})}
    private fun card(label:String,value:String,sub:String=""){content.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,17,20,17);setBackgroundColor(panel);elevation=3f;addView(TextView(this@MainActivity).apply{text=label;textSize=12f;setTextColor(muted)});addView(TextView(this@MainActivity).apply{text=value;textSize=25f;setTextColor(gold);setTypeface(typeface,1)});if(sub.isNotBlank())addView(TextView(this@MainActivity).apply{text=sub;textSize=11f;setTextColor(muted)})},margin())}
    private fun input(hint:String)=EditText(this).apply{this.hint=hint;setHintTextColor(Color.rgb(125,130,140));setTextColor(Color.WHITE);setBackgroundColor(panel);setPadding(17,13,17,13)}
    private fun button(text:String,action:()->Unit)=MaterialButton(this).apply{this.text=text;setTextColor(dark);setBackgroundColor(gold);setOnClickListener{action()}}
    private fun secondary(text:String,action:()->Unit)=MaterialButton(this).apply{this.text=text;setTextColor(Color.WHITE);setBackgroundColor(Color.rgb(55,58,65));setOnClickListener{action()}}
    private fun box()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(18,16,18,16);setBackgroundColor(panel)}
    private fun txt(t:String,size:Int,bold:Boolean)=TextView(this).apply{text=t;textSize=size.toFloat();setTextColor(if(bold)Color.WHITE else muted);if(bold)setTypeface(typeface,1);setPadding(0,3,0,6)}
    private fun empty(t:String){content.addView(txt(t,12,false))}
    private fun margin()=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,6,0,10)}
    private fun toast(t:String)=Toast.makeText(this,t,Toast.LENGTH_LONG).show()
}
