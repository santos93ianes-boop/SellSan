package com.sellsan.app

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
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
    private val gold = Color.rgb(228, 189, 87)
    private val dark = Color.rgb(10, 11, 14)
    private val panel = Color.rgb(25, 28, 33)
    private val panel2 = Color.rgb(18, 20, 24)
    private val muted = Color.rgb(174, 180, 191)
    private val green = Color.rgb(37, 211, 102)
    private val blue = Color.rgb(73, 111, 255)
    private val statuses = listOf("Novo", "Conversando", "Orçamento", "Follow-up", "Fechado", "Perdido")
    private var voiceTarget: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark
        window.navigationBarColor = dark
        requestNotificationPermission()
        if (prefs.getString("server_url", "").isNullOrBlank() && BuildConfig.DEFAULT_SERVER_URL.isNotBlank()) {
            prefs.edit().putString("server_url", BuildConfig.DEFAULT_SERVER_URL).apply()
        }
        if (intent?.data?.scheme == "sellsan" && intent.data?.host == "whatsapp-connected") { handleDeepLink(intent); return }
        if (!prefs.getBoolean("intro_done", false)) showWelcome() else showShell("dashboard")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(i: Intent?) {
        if (i?.data?.scheme == "sellsan" && i.data?.host == "whatsapp-connected") {
            prefs.edit().putBoolean("intro_done", true).apply()
            showShell("whatsapp")
            Handler(Looper.getMainLooper()).postDelayed({ toast("WhatsApp conectado. Verificando status…") }, 350)
        }
    }

    private fun showWelcome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(38), dp(26), dp(28))
            setBackgroundColor(dark)
        }
        val logo = ImageView(this).apply { setImageResource(com.sellsan.app.R.drawable.ic_sellsan); scaleType = ImageView.ScaleType.CENTER_INSIDE }
        root.addView(logo, LinearLayout.LayoutParams(dp(150), dp(150)))
        root.addView(TextView(this).apply {
            text = "SellSan"; textSize = 38f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Seu assistente inteligente de vendas e serviços"; textSize = 15f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(22))
        })
        root.addView(TextView(this).apply {
            text = "WhatsApp + IA + Orçamentos + Clientes\nTudo em um só lugar."; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(22))
        })
        root.addView(button("ENTRAR NO SELLSAN") { prefs.edit().putBoolean("intro_done", true).apply(); showShell("more") })
        root.addView(secondary("CONTINUAR EM MODO LOCAL") { prefs.edit().putBoolean("intro_done", true).apply(); showShell("dashboard") })
        root.addView(TextView(this).apply { text = "Conecte o WhatsApp Business oficial quando quiser liberar o atendimento automático."; textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        setContentView(ScrollView(this).apply { setBackgroundColor(dark); addView(root) })
    }

    private fun showShell(screen: String) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(dark); fitsSystemWindows = true }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(12)); setBackgroundColor(panel2)
        }
        header.addView(ImageView(this).apply { setImageResource(com.sellsan.app.R.drawable.ic_sellsan) }, LinearLayout.LayoutParams(dp(46), dp(46)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        brand.addView(TextView(this).apply { text = "SellSan"; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        brand.addView(TextView(this).apply { text = if (isLogged()) "Seu negócio • Nuvem ativa" else "Seu assistente inteligente de vendas"; textSize = 11f; setTextColor(muted) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(this).apply { text = if (isLogged()) "● ONLINE" else "○ LOCAL"; textSize = 10f; setTextColor(if (isLogged()) green else muted) })
        root.addView(header)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(8)); setBackgroundColor(panel2) }
        val items = listOf("⌂\nInício" to "dashboard", "◉\nWhatsApp" to "whatsapp", "♟\nClientes" to "clients", "▣\nVendas" to "quotes", "☰\nMais" to "more")
        items.forEach { (label, id) -> nav.addView(bottomNavButton(label, screen == id) { showShell(id) }, LinearLayout.LayoutParams(0, dp(62), 1f)) }
        root.addView(nav)
        setContentView(root)

        when (screen) {
            "whatsapp" -> whatsappScreen()
            "clients" -> clientsScreen()
            "quotes" -> quotesScreen()
            "funnel" -> funnelScreen()
            "agenda" -> agendaScreen()
            "ai" -> aiScreen()
            "results" -> resultsScreen()
            "settings" -> settingsScreen()
            "more" -> moreScreen()
            else -> dashboardScreen()
        }
    }

    private fun dashboardScreen() {
        val company = prefs.getString("company", "")!!.ifBlank { "Minha Empresa" }
        title(company, "Painel inteligente • hoje")
        val clients = getArray("clients"); val quotes = getArray("quotes")
        var open = 0; var potential = 0.0; var won = 0.0; var follow = 0
        for (i in 0 until quotes.length()) {
            val q = quotes.getJSONObject(i)
            when (q.optString("status", "Novo")) {
                "Fechado" -> won += q.optDouble("value")
                "Perdido" -> Unit
                else -> { open++; potential += q.optDouble("value"); if (q.optString("status") == "Follow-up") follow++ }
            }
        }
        val hero = roundedBox(panel, 22f).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(txt("Vendas fechadas", 13, false))
            addView(TextView(this@MainActivity).apply { text = brl.format(won); textSize = 30f; setTextColor(gold); typeface = Typeface.DEFAULT_BOLD })
            addView(txt(if (won > 0) "Continue acompanhando seus clientes" else "Sua primeira venda aparecerá aqui", 12, false))
        }
        content.addView(hero, margin())

        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid.addView(metricCard("Em negociação", brl.format(potential), "$open oportunidades", blue), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(6), 0) })
        grid.addView(metricCard("Clientes", clients.length().toString(), "$follow follow-ups", green), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(6), 0, 0, 0) })
        content.addView(grid, margin())

        section("Ações rápidas")
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick.addView(actionTile("💬", "WhatsApp IA", green) { showShell("whatsapp") }, LinearLayout.LayoutParams(0, dp(120), 1f).apply { setMargins(0, 0, dp(6), 0) })
        quick.addView(actionTile("👤", "Novo cliente", blue) { showShell("clients") }, LinearLayout.LayoutParams(0, dp(120), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        content.addView(quick, margin())
        val quick2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quick2.addView(actionTile("▣", "Orçamento", Color.rgb(239, 142, 34)) { showShell("quotes") }, LinearLayout.LayoutParams(0, dp(120), 1f).apply { setMargins(0, 0, dp(6), 0) })
        quick2.addView(actionTile("◫", "Agenda", Color.rgb(107, 78, 235)) { showShell("agenda") }, LinearLayout.LayoutParams(0, dp(120), 1f).apply { setMargins(dp(6), 0, 0, 0) })
        content.addView(quick2, margin())

        section("O que fazer agora?")
        val hint = when {
            follow > 0 -> "Você tem $follow cliente(s) aguardando follow-up."
            open > 0 -> "Revise $open oportunidade(s) abertas e tente avançar a negociação."
            clients.length() == 0 -> "Cadastre seu primeiro cliente ou conecte o WhatsApp para receber contatos automaticamente."
            else -> "Tudo organizado. Continue alimentando novas oportunidades."
        }
        val h = roundedBox(panel, 18f).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); addView(txt("✓", 18, true)); addView(txt(hint, 14, false), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(10), 0, 0, 0) }) }
        content.addView(h, margin())
    }

    private fun whatsappScreen() {
        title("WhatsApp IA", "Conecte seu número oficial e deixe o SellSan atender, organizar e orçar.")
        val statusBox = roundedBox(panel, 20f).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)) }
        val statusTitle = txt("Verificando conexão…", 18, true)
        val statusSub = txt("A integração usa a WhatsApp Business Platform oficial da Meta.", 12, false)
        statusBox.addView(statusTitle); statusBox.addView(statusSub)
        content.addView(statusBox, margin())

        val connectBtn = button("💬  CONECTAR COM A META") { startWhatsappConnect() }
        content.addView(connectBtn, margin())
        content.addView(secondary("Atualizar status") { loadWhatsappStatus(statusTitle, statusSub) })
        loadWhatsappStatus(statusTitle, statusSub)

        section("Modo de atendimento")
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("IA automática" to "auto", "IA sugere" to "assist", "Humano" to "manual").forEachIndexed { idx, pair ->
            modeRow.addView(smallModeButton(pair.first) { setWhatsappMode(pair.second) }, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(if (idx == 0) 0 else dp(4), 0, if (idx == 2) 0 else dp(4), 0) })
        }
        content.addView(modeRow, margin())

        section("Conversas")
        val conversations = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(conversations)
        content.addView(secondary("Atualizar conversas") { loadConversations(conversations) })
        loadConversations(conversations)

        section("Serviços e preços")
        val serviceName = input("Nome do serviço")
        val servicePrice = input("Preço base (ex.: 450,00)")
        val serviceKeywords = input("Palavras que o cliente costuma usar")
        content.addView(serviceName); content.addView(servicePrice); content.addView(serviceKeywords)
        content.addView(button("+ CADASTRAR SERVIÇO") {
            if (!isLogged()) return@button toast("Entre na sua conta SellSan primeiro")
            val price = parseMoney(servicePrice.text.toString()); if (serviceName.text.isBlank() || price <= 0) return@button toast("Informe serviço e preço")
            val b = JSONObject().put("name", serviceName.text.toString()).put("price", price).put("keywords", serviceKeywords.text.toString()).put("unit", "serviço")
            thread { val r = CloudClient.request(serverUrl(), "/api/services", "POST", token(), b); runOnUiThread { toast(if (r.ok) "Serviço cadastrado para a IA" else r.message) } }
        })

        section("Enviar manualmente")
        val phone = input("Telefone com DDD e país")
        val message = input("Mensagem").apply { minLines = 3 }
        content.addView(phone); content.addView(message)
        content.addView(secondary("Enviar pelo WhatsApp Business") {
            if (!isLogged()) return@secondary toast("Entre na conta primeiro")
            val b = JSONObject().put("to", phone.text.toString()).put("body", message.text.toString())
            thread { val r = CloudClient.request(serverUrl(), "/api/whatsapp/send", "POST", token(), b); runOnUiThread { toast(if (r.ok) "Mensagem enviada" else r.message) } }
        })
    }

    private fun startWhatsappConnect() {
        if (!isLogged()) { toast("Primeiro entre ou crie sua conta SellSan em Mais → Conta"); showShell("settings"); return }
        thread {
            val r = CloudClient.request(serverUrl(), "/api/whatsapp/connect-session", "POST", token(), JSONObject())
            runOnUiThread {
                if (!r.ok) toast(r.message) else {
                    val url = r.body?.optString("url").orEmpty()
                    if (url.isBlank()) toast("Servidor não retornou a conexão") else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        }
    }

    private fun loadWhatsappStatus(title: TextView, sub: TextView) {
        if (!isLogged()) { title.text = "WhatsApp ainda não conectado"; title.setTextColor(muted); sub.text = "Entre na conta SellSan e toque em Conectar com a Meta."; return }
        thread {
            val r = CloudClient.request(serverUrl(), "/api/whatsapp/status", "GET", token())
            runOnUiThread {
                if (r.ok && r.body?.optBoolean("configured") == true) {
                    title.text = "● WhatsApp Business conectado"; title.setTextColor(green)
                    val phone = r.body.optString("displayPhone").ifBlank { "Número conectado" }
                    val mode = when (r.body.optString("mode")) { "auto" -> "IA automática"; "manual" -> "Humano"; else -> "IA sugere" }
                    sub.text = "$phone • $mode"
                } else {
                    title.text = "○ Conecte seu WhatsApp Business"; title.setTextColor(Color.WHITE)
                    sub.text = if (r.ok) "Toque em Conectar com a Meta. Não é necessário copiar tokens no celular." else r.message
                }
            }
        }
    }

    private fun setWhatsappMode(mode: String) {
        if (!isLogged()) return toast("Entre na conta primeiro")
        thread { val r = CloudClient.request(serverUrl(), "/api/whatsapp/mode", "POST", token(), JSONObject().put("mode", mode)); runOnUiThread { toast(if (r.ok) "Modo de atendimento atualizado" else r.message) } }
    }

    private fun loadConversations(target: LinearLayout) {
        if (!isLogged()) { target.removeAllViews(); target.addView(txt("Conecte sua conta e o WhatsApp para receber conversas aqui.", 13, false)); return }
        thread {
            val r = CloudClient.request(serverUrl(), "/api/whatsapp/messages", "GET", token())
            runOnUiThread {
                target.removeAllViews(); val a = r.body?.optJSONArray("messages") ?: JSONArray()
                if (!r.ok || a.length() == 0) target.addView(txt(if (r.ok) "Nenhuma conversa recebida ainda." else r.message, 13, false))
                for (i in 0 until minOf(a.length(), 30)) {
                    val m = a.getJSONObject(i); val inbound = m.optString("direction") == "in"
                    val b = roundedBox(if (inbound) Color.rgb(31, 34, 40) else Color.rgb(29, 61, 43), 16f).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
                    b.addView(txt((if (inbound) "Cliente" else "SellSan") + " • " + m.optString("waId"), 12, true)); b.addView(txt(m.optString("body"), 14, false)); target.addView(b, margin())
                }
            }
        }
    }

    private fun clientsScreen() {
        title("Clientes", "Contatos organizados e histórico comercial em um só lugar.")
        val name = input("Nome do cliente"); val phone = input("WhatsApp com DDD"); val need = input("Serviço / necessidade")
        content.addView(name); content.addView(phone); content.addView(need)
        content.addView(button("SALVAR CLIENTE") {
            if (name.text.isBlank()) return@button toast("Informe o nome")
            val a = getArray("clients"); a.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", name.text.toString()).put("phone", phone.text.toString()).put("need", need.text.toString()).put("created", now())); saveArray("clients", a); autoSync(); showShell("clients")
        })
        section("Clientes salvos"); val a = getArray("clients"); if (a.length() == 0) empty("Nenhum cliente cadastrado.")
        for (i in a.length() - 1 downTo 0) {
            val c = a.getJSONObject(i); val b = roundedBox(panel, 18f).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
            b.addView(txt(c.optString("name"), 16, true)); b.addView(txt(c.optString("phone") + "\n" + c.optString("need"), 13, false)); b.addView(secondary("Abrir WhatsApp") { openWhatsApp(c.optString("phone"), "Olá ${c.optString("name")}! Tudo bem? Estou entrando em contato sobre ${c.optString("need")}.") }); content.addView(b, margin())
        }
    }

    private fun quotesScreen() {
        title("Vendas e orçamentos", "Crie, envie e acompanhe cada oportunidade até o fechamento.")
        val client = input("Cliente"); val phone = input("WhatsApp"); val service = input("Serviço / produto"); val value = input("Valor (ex.: 850,00)"); val details = input("Condições / observações")
        listOf(client, phone, service, value, details).forEach { content.addView(it) }
        content.addView(button("CRIAR ORÇAMENTO") {
            val v = parseMoney(value.text.toString()); if (client.text.isBlank() || service.text.isBlank()) return@button toast("Preencha cliente e serviço")
            val a = getArray("quotes"); a.put(JSONObject().put("id", UUID.randomUUID().toString()).put("client", client.text.toString()).put("phone", phone.text.toString()).put("service", service.text.toString()).put("value", v).put("details", details.text.toString()).put("status", "Novo").put("created", now())); saveArray("quotes", a); autoSync(); showShell("quotes")
        })
        content.addView(secondary("Enviar prévia pelo WhatsApp") { openWhatsApp(phone.text.toString(), quoteMessage(client.text.toString(), service.text.toString(), parseMoney(value.text.toString()), details.text.toString())) })
        section("Orçamentos")
        val a = getArray("quotes"); if (a.length() == 0) empty("Nenhum orçamento criado.")
        for (i in a.length() - 1 downTo 0) {
            val q = a.getJSONObject(i); val b = roundedBox(panel, 18f).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)) }
            b.addView(txt("${q.optString("client")} • ${brl.format(q.optDouble("value"))}", 16, true)); b.addView(txt("${q.optString("service")}\n${q.optString("status")}", 12, false))
            val sp = Spinner(this); sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, statuses); sp.setSelection(statuses.indexOf(q.optString("status", "Novo")).coerceAtLeast(0)); sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onNothingSelected(parent: AdapterView<*>?) {} ; override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { val ns = statuses[position]; if (q.optString("status", "Novo") != ns) { q.put("status", ns); saveArray("quotes", a); autoSync() } } }; b.addView(sp)
            b.addView(secondary("WhatsApp") { openWhatsApp(q.optString("phone"), quoteMessage(q.optString("client"), q.optString("service"), q.optDouble("value"), q.optString("details"))) }); b.addView(secondary("Gerar PDF") { shareQuotePdf(q) }); b.addView(secondary("Lembrar amanhã") { scheduleFollowUp(q.optString("client"), "Retorne o orçamento de ${brl.format(q.optDouble("value"))} para ${q.optString("client")}.") }); content.addView(b, margin())
        }
    }

    private fun funnelScreen() {
        title("Funil de vendas", "Veja onde cada oportunidade está e o que precisa avançar.")
        val a = getArray("quotes")
        statuses.forEach { st ->
            val list = mutableListOf<Int>(); var total = 0.0
            for (i in 0 until a.length()) { val q = a.getJSONObject(i); if (q.optString("status", "Novo") == st) { list.add(i); total += q.optDouble("value") } }
            section("$st • ${list.size} • ${brl.format(total)}")
            if (list.isEmpty()) empty("Sem oportunidades aqui.")
            list.forEach { idx -> val q = a.getJSONObject(idx); val b = roundedBox(panel, 16f).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }; b.addView(txt("${q.optString("client")} — ${brl.format(q.optDouble("value"))}", 15, true)); b.addView(txt(q.optString("service"), 12, false)); content.addView(b, margin()) }
        }
    }

    private fun agendaScreen() {
        title("Agenda", "Transforme vendas em atendimentos e mantenha os próximos passos sob controle.")
        val client = input("Cliente"); val date = input("Data e hora (ex.: 15/09 14:00)"); val service = input("Serviço")
        content.addView(client); content.addView(date); content.addView(service)
        content.addView(button("AGENDAR") { if (client.text.isBlank() || date.text.isBlank()) return@button toast("Informe cliente e data"); val a = getArray("agenda"); a.put(JSONObject().put("id", UUID.randomUUID().toString()).put("client", client.text.toString()).put("date", date.text.toString()).put("service", service.text.toString()).put("done", false)); saveArray("agenda", a); autoSync(); showShell("agenda") })
        section("Próximos atendimentos"); val a = getArray("agenda"); if (a.length() == 0) empty("Nenhum atendimento agendado.")
        for (i in 0 until a.length()) { val x = a.getJSONObject(i); val b = roundedBox(panel, 16f).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)) }; b.addView(txt("${x.optString("date")} • ${x.optString("client")}", 15, true)); b.addView(txt(x.optString("service"), 12, false)); content.addView(b, margin()) }
    }

    private fun aiScreen() {
        title("SellSan IA", if (isLogged()) "IA conectada aos dados comerciais da sua conta." else "Entre na sua conta para usar a IA online.")
        val prompt = input("Ex.: Quais clientes devo chamar hoje?").apply { minLines = 5 }; voiceTarget = prompt; content.addView(prompt)
        content.addView(secondary("🎙 Falar com SellSan") { startVoice(prompt) })
        val out = roundedText("A resposta aparecerá aqui.", 15, panel)
        content.addView(button("✦ ANALISAR COM IA") { val p = prompt.text.toString().trim(); if (p.isBlank()) return@button toast("Digite uma pergunta"); out.text = "Analisando…"; callAi(p) { answer -> out.text = answer } })
        content.addView(out, margin())
    }

    private fun resultsScreen() {
        title("Resultados", "Indicadores simples para saber se o negócio está avançando.")
        val a = getArray("quotes"); var won = 0.0; var lost = 0.0; var open = 0.0; var wonCount = 0; var lostCount = 0
        for (i in 0 until a.length()) { val q = a.getJSONObject(i); when (q.optString("status", "Novo")) { "Fechado" -> { won += q.optDouble("value"); wonCount++ }; "Perdido" -> { lost += q.optDouble("value"); lostCount++ }; else -> open += q.optDouble("value") } }
        card("Faturamento", brl.format(won), "$wonCount venda(s) fechada(s)"); card("Em negociação", brl.format(open)); card("Perdido", brl.format(lost), "$lostCount oportunidade(s)")
        val total = wonCount + lostCount; val conversion = if (total > 0) wonCount * 100.0 / total else 0.0; card("Conversão", String.format(Locale("pt", "BR"), "%.1f%%", conversion))
    }

    private fun moreScreen() {
        title("Mais", "Gerencie sua operação sem poluir a tela principal.")
        menuCard("◫", "Agenda", "Próximos atendimentos") { showShell("agenda") }
        menuCard("▽", "Funil de vendas", "Acompanhe oportunidades") { showShell("funnel") }
        menuCard("✦", "SellSan IA", "Análises e mensagens") { showShell("ai") }
        menuCard("▥", "Resultados", "Vendas, conversão e potencial") { showShell("results") }
        menuCard("⚙", "Conta e empresa", "Login, nuvem e configurações") { showShell("settings") }
    }

    private fun settingsScreen() {
        title("Conta e empresa", "Configure somente o essencial. O servidor fica oculto para o cliente final quando definido no build.")
        val company = input("Nome da empresa").apply { setText(prefs.getString("company", "")) }
        val seller = input("Seu nome").apply { setText(prefs.getString("seller", "")) }
        content.addView(company); content.addView(seller)
        content.addView(button("SALVAR EMPRESA") { prefs.edit().putString("company", company.text.toString()).putString("seller", seller.text.toString()).apply(); toast("Empresa salva") })
        section(if (isLogged()) "Conta conectada" else "Entrar ou criar conta")
        if (isLogged()) {
            content.addView(roundedText("Conectado como ${prefs.getString("user_email", "")}", 14, panel))
            content.addView(button("SINCRONIZAR NUVEM") { syncNow(true) })
            content.addView(secondary("Sair da conta") { prefs.edit().remove("auth_token").remove("user_email").apply(); showShell("settings") })
        } else {
            val email = input("E-mail"); val pass = input("Senha (mínimo 6 caracteres)").apply { inputType = 0x00000081 }
            content.addView(email); content.addView(pass)
            content.addView(button("ENTRAR") { login(email.text.toString(), pass.text.toString()) })
            content.addView(secondary("CRIAR CONTA SELLSAN") { register(company.text.toString(), seller.text.toString(), email.text.toString(), pass.text.toString()) })
        }
        if (BuildConfig.DEFAULT_SERVER_URL.isBlank()) {
            section("Configuração avançada")
            val server = input("URL HTTPS do servidor SellSan").apply { setText(prefs.getString("server_url", "")) }
            content.addView(server)
            content.addView(secondary("Salvar servidor") { prefs.edit().putString("server_url", server.text.toString().trim()).apply(); toast("Servidor salvo") })
            content.addView(secondary("Verificar conexão") { checkServer() })
        }
    }

    private fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) return toast("Informe e-mail e senha")
        if (serverUrl().isBlank()) return toast("Servidor SellSan ainda não configurado")
        thread { val r = CloudClient.request(serverUrl(), "/api/auth/login", "POST", body = JSONObject().put("email", email).put("password", password)); runOnUiThread { if (r.ok) { prefs.edit().putString("auth_token", r.body?.optString("token")).putString("user_email", email).apply(); toast("Conta conectada"); syncNow(true) } else toast(r.message) } }
    }

    private fun register(company: String, name: String, email: String, password: String) {
        if (company.isBlank() || name.isBlank() || email.isBlank() || password.length < 6) return toast("Preencha empresa, nome, e-mail e senha")
        if (serverUrl().isBlank()) return toast("Servidor SellSan ainda não configurado")
        thread { val r = CloudClient.request(serverUrl(), "/api/auth/register", "POST", body = JSONObject().put("company", company).put("name", name).put("email", email).put("password", password)); runOnUiThread { if (r.ok) { prefs.edit().putString("auth_token", r.body?.optString("token")).putString("user_email", email).putString("company", company).putString("seller", name).apply(); toast("Conta criada"); syncNow(true) } else toast(r.message) } }
    }

    private fun checkServer() { if (serverUrl().isBlank()) return toast("Informe a URL do servidor"); thread { val r = CloudClient.request(serverUrl(), "/api/health"); runOnUiThread { toast(if (r.ok) "Servidor online" else "Falha: ${r.message}") } } }

    private fun syncNow(showToast: Boolean) {
        if (!isLogged()) { if (showToast) toast("Entre na sua conta primeiro"); return }
        val server = serverUrl(); val t = token(); val local = snapshot()
        thread {
            val pull = CloudClient.request(server, "/api/sync", "GET", t)
            if (!pull.ok) { runOnUiThread { if (showToast) toast("Falha na sincronização: ${pull.message}") }; return@thread }
            val merged = mergeSnapshots(pull.body ?: JSONObject(), local)
            val push = CloudClient.request(server, "/api/sync", "PUT", t, merged)
            if (push.ok) { applySnapshot(merged); runOnUiThread { if (showToast) toast("Sincronizado com a nuvem"); showShell("dashboard") } } else runOnUiThread { if (showToast) toast("Falha na sincronização: ${push.message}") }
        }
    }

    private fun mergeSnapshots(remote: JSONObject, local: JSONObject): JSONObject {
        fun mergeArray(key: String): JSONArray { val map = linkedMapOf<String, JSONObject>(); val r = remote.optJSONArray(key) ?: JSONArray(); for (i in 0 until r.length()) { val o = r.optJSONObject(i) ?: continue; map[o.optString("id", o.toString())] = o }; val l = local.optJSONArray(key) ?: JSONArray(); for (i in 0 until l.length()) { val o = l.optJSONObject(i) ?: continue; map[o.optString("id", o.toString())] = o }; return JSONArray().apply { map.values.forEach { put(it) } } }
        return JSONObject().put("clients", mergeArray("clients")).put("quotes", mergeArray("quotes")).put("agenda", mergeArray("agenda")).put("company", local.optString("company", remote.optString("company"))).put("seller", local.optString("seller", remote.optString("seller"))).put("updatedAt", System.currentTimeMillis())
    }

    private fun autoSync() { if (isLogged()) thread { CloudClient.request(serverUrl(), "/api/sync", "PUT", token(), snapshot()) } }
    private fun callAi(prompt: String, done: (String) -> Unit) { if (!isLogged()) { done(localReply(prompt) + "\n\nEntre na conta para usar a IA online."); return }; val body = JSONObject().put("prompt", prompt).put("businessContext", snapshot()); thread { val r = CloudClient.request(serverUrl(), "/api/ai", "POST", token(), body); val answer = if (r.ok) r.body?.optString("answer").orEmpty() else "Não foi possível acessar a IA: ${r.message}\n\n${localReply(prompt)}"; Handler(Looper.getMainLooper()).post { done(answer) } } }
    private fun snapshot() = JSONObject().put("clients", getArray("clients")).put("quotes", getArray("quotes")).put("agenda", getArray("agenda")).put("company", prefs.getString("company", "")).put("seller", prefs.getString("seller", "")).put("updatedAt", System.currentTimeMillis())
    private fun applySnapshot(j: JSONObject) { prefs.edit().putString("clients", j.optJSONArray("clients")?.toString() ?: "[]").putString("quotes", j.optJSONArray("quotes")?.toString() ?: "[]").putString("agenda", j.optJSONArray("agenda")?.toString() ?: "[]").apply() }

    private fun shareQuotePdf(q: JSONObject) {
        try {
            val pdf = PdfDocument(); val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()); val c = page.canvas
            val p = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true }; c.drawText("SELLSAN • ORÇAMENTO", 42f, 60f, p)
            p.textSize = 12f; p.isFakeBoldText = false
            val lines = listOf("Empresa: ${prefs.getString("company", "SellSan")}", "Cliente: ${q.optString("client")}", "Data: ${q.optString("created")}", "Serviço: ${q.optString("service")}", "Valor: ${brl.format(q.optDouble("value"))}", "Status: ${q.optString("status")}", "Condições: ${q.optString("details").ifBlank { "A combinar" }}")
            var y = 105f; lines.forEach { c.drawText(it.take(80), 42f, y, p); y += 30f }; p.textSize = 10f; c.drawText("Gerado pelo SellSan", 42f, 790f, p); pdf.finishPage(page)
            val file = File(cacheDir, "orcamento_${q.optString("client").replace(" ", "_")}.pdf"); FileOutputStream(file).use { pdf.writeTo(it) }; pdf.close()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file); startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Compartilhar orçamento"))
        } catch (e: Exception) { toast("Falha ao gerar PDF: ${e.message}") }
    }

    private fun scheduleFollowUp(client: String, text: String) { val intent = Intent(this, ReminderReceiver::class.java).putExtra("client", client).putExtra("text", text); val pi = PendingIntent.getBroadcast(this, (System.currentTimeMillis() % Int.MAX_VALUE).toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE); val alarm = getSystemService(ALARM_SERVICE) as AlarmManager; alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 24 * 60 * 60 * 1000, pi); toast("Lembrete agendado para amanhã") }
    private fun requestNotificationPermission() { if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31) }
    private fun startVoice(target: EditText) { voiceTarget = target; try { startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR") }, 900) } catch (_: Exception) { toast("Reconhecimento de voz indisponível") } }
    @Deprecated("Deprecated in Java") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == 900 && resultCode == RESULT_OK) voiceTarget?.setText(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()) }

    private fun localReply(p: String): String { val l = p.lowercase(); val q = getArray("quotes"); return when { l.contains("quem") && l.contains("chamar") -> { val names = mutableListOf<String>(); for (i in 0 until q.length()) { val x = q.getJSONObject(i); if (x.optString("status") !in listOf("Fechado", "Perdido")) names.add(x.optString("client")) }; if (names.isEmpty()) "Você ainda não tem oportunidades abertas." else "Priorize hoje: ${names.take(5).joinToString(", ")}." }; l.contains("follow") || l.contains("mensagem") -> "Olá! Passando para saber se conseguiu avaliar o orçamento. Se tiver alguma dúvida, posso ajudar."; else -> "Posso ajudar com clientes, orçamentos, follow-up e próximos passos." } }
    private fun quoteMessage(client: String, service: String, value: Double, details: String): String { val company = prefs.getString("company", "SellSan")!!.ifBlank { "SellSan" }; return "Olá $client! Segue o orçamento da $company:\n\n$service\nValor: ${brl.format(value)}\n${details.ifBlank { "Condições a combinar." }}\n\nSe estiver de acordo, me confirme para reservarmos o atendimento." }
    private fun openWhatsApp(phone: String, message: String) { try { val clean = phone.filter { it.isDigit() }.removePrefix("55"); val encoded = URLEncoder.encode(message, "UTF-8"); val url = if (clean.isBlank()) "https://wa.me/?text=$encoded" else "https://wa.me/55$clean?text=$encoded"; startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { toast("Não foi possível abrir o WhatsApp") } }

    private fun parseMoney(s: String) = s.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
    private fun serverUrl() = prefs.getString("server_url", BuildConfig.DEFAULT_SERVER_URL)!!.trim().trimEnd('/')
    private fun token() = prefs.getString("auth_token", "")!!.trim()
    private fun isLogged() = token().isNotBlank() && serverUrl().isNotBlank()
    private fun now() = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
    private fun getArray(key: String) = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
    private fun saveArray(key: String, arr: JSONArray) { prefs.edit().putString(key, arr.toString()).apply() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(color: Int, radius: Float, stroke: Int? = null, strokeWidth: Int = 1) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat(); if (stroke != null) setStroke(dp(strokeWidth), stroke) }
    private fun roundedBox(color: Int, radius: Float) = LinearLayout(this).apply { background = bg(color, radius) }
    private fun title(text: String, subtitle: String = "") { content.addView(TextView(this).apply { this.text = text; textSize = 27f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }); if (subtitle.isNotBlank()) content.addView(TextView(this).apply { this.text = subtitle; textSize = 13f; setTextColor(muted); setPadding(0, dp(4), 0, dp(16)) }) }
    private fun section(text: String) { content.addView(TextView(this).apply { this.text = text; textSize = 18f; setTextColor(gold); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(18), 0, dp(10)) }) }
    private fun card(label: String, value: String, sub: String = "") { val b = roundedBox(panel, 18f).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); addView(txt(label, 13, false)); addView(TextView(this@MainActivity).apply { text = value; textSize = 27f; setTextColor(gold); typeface = Typeface.DEFAULT_BOLD }); if (sub.isNotBlank()) addView(txt(sub, 12, false)) }; content.addView(b, margin()) }
    private fun metricCard(label: String, value: String, sub: String, accent: Int): View = roundedBox(panel, 18f).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); addView(TextView(this@MainActivity).apply { text = label; textSize = 12f; setTextColor(muted) }); addView(TextView(this@MainActivity).apply { text = value; textSize = 20f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD }); addView(TextView(this@MainActivity).apply { text = sub; textSize = 11f; setTextColor(muted) }) }
    private fun actionTile(icon: String, label: String, color: Int, action: () -> Unit): View = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = bg(color, 18f); setPadding(dp(10), dp(12), dp(10), dp(12)); addView(TextView(this@MainActivity).apply { text = icon; textSize = 28f; gravity = Gravity.CENTER }); addView(TextView(this@MainActivity).apply { text = label; textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }); setOnClickListener { action() } }
    private fun menuCard(icon: String, title: String, sub: String, action: () -> Unit) { val r = roundedBox(panel, 18f).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); addView(TextView(this@MainActivity).apply { text = icon; textSize = 24f; setTextColor(gold); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(44), dp(44))); val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(txt(title, 15, true)); addView(txt(sub, 12, false)) }; addView(t, LinearLayout.LayoutParams(0, -2, 1f)); addView(txt("›", 24, true)); setOnClickListener { action() } }; content.addView(r, margin()) }
    private fun input(hint: String) = EditText(this).apply { this.hint = hint; setHintTextColor(Color.rgb(125, 130, 140)); setTextColor(Color.WHITE); background = bg(panel, 14f); setPadding(dp(16), dp(14), dp(16), dp(14)); textSize = 15f; layoutParams = margin() }
    private fun button(text: String, action: () -> Unit) = MaterialButton(this).apply { this.text = text; textSize = 14f; setTextColor(dark); backgroundTintList = android.content.res.ColorStateList.valueOf(gold); cornerRadius = dp(14); minHeight = dp(54); setOnClickListener { action() } }
    private fun secondary(text: String, action: () -> Unit) = MaterialButton(this).apply { this.text = text; textSize = 13f; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 52, 60)); cornerRadius = dp(14); minHeight = dp(50); setOnClickListener { action() } }
    private fun smallModeButton(text: String, action: () -> Unit) = MaterialButton(this).apply { this.text = text; textSize = 10f; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(42, 46, 54)); cornerRadius = dp(12); setOnClickListener { action() } }
    private fun bottomNavButton(text: String, active: Boolean, action: () -> Unit) = MaterialButton(this).apply { this.text = text; textSize = 10f; gravity = Gravity.CENTER; setTextColor(if (active) gold else muted); backgroundTintList = android.content.res.ColorStateList.valueOf(if (active) Color.rgb(34, 36, 42) else panel2); cornerRadius = dp(12); insetTop = 0; insetBottom = 0; setOnClickListener { action() } }
    private fun txt(t: String, size: Int, bold: Boolean) = TextView(this).apply { text = t; textSize = size.toFloat(); setTextColor(if (bold) Color.WHITE else muted); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(2), 0, dp(4)) }
    private fun roundedText(t: String, size: Int, color: Int) = TextView(this).apply { text = t; textSize = size.toFloat(); setTextColor(Color.WHITE); background = bg(color, 16f); setPadding(dp(16), dp(16), dp(16), dp(16)) }
    private fun empty(t: String) { content.addView(txt(t, 13, false)) }
    private fun margin() = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(8)) }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
