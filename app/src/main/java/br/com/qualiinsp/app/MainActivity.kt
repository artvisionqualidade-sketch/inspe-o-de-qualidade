package br.com.qualiinsp.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.view.View
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private lateinit var beacon: TextView
    private lateinit var store: Store
    private var captureAction = "inspection"
    private var liveRequested = false
    private var videoRequested = false
    private var selectedProduct: Product? = null
    private var selectedDefectId: String? = null
    private var pendingStandardView: ((Bitmap)->Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var blinkOn = false
    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data?.getBooleanExtra(CameraXCaptureActivity.EXTRA_LIVE_RESULT, false) == true) {
                val p = selectedProduct
                if (p != null) {
                    val status = when (data.getStringExtra(CameraXCaptureActivity.EXTRA_LIVE_STATUS)) {
                        "OK" -> Status.OK
                        "BAD" -> Status.BAD
                        else -> Status.ATTENTION
                    }
                    val reason = data.getStringExtra(CameraXCaptureActivity.EXTRA_LIVE_REASON) ?: "Inspeção inconclusiva"
                    val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                    store.addInspection(Inspection(now, p.code, p.name, status.name, reason, 0f, currentMeta.lot, currentMeta.machine, currentMeta.mold, currentMeta.cavity))
                    resultDialog(status, "Resultado da inspeção ao vivo", reason + "\n\nA classificação visual é preliminar e precisa ser validada com amostras reais antes de uso industrial.")
                }
            } else {
                val videoPath = data?.getStringExtra(CameraXCaptureActivity.EXTRA_VIDEO_PATH)
                if (!videoPath.isNullOrBlank()) {
                    val p = selectedProduct
                    if (p != null) analyzeRecordedVideo(p, videoPath)
                    else toast("Selecione um produto antes de analisar o vídeo")
                } else {
                    val path = data?.getStringExtra(CameraXCaptureActivity.EXTRA_IMAGE_PATH)
                    val bmp = path?.let { BitmapFactory.decodeFile(it) }
                    if (bmp != null) onCaptured(bmp) else { setStatus(Status.WAITING); toast("Não foi possível carregar a foto capturada") }
                }
            }
        } else setStatus(Status.WAITING)
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) onCaptured(bmp) else toast("Não foi possível abrir essa imagem")
            } catch (e: Exception) {
                toast("Erro ao carregar a imagem: ${e.localizedMessage ?: "arquivo inválido"}")
            }
        }
    }
    // Seletor nativo do Android; em aparelhos compatíveis pode incluir provedores
    // de mídia na nuvem, como o Google Fotos.
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (bmp != null) onCaptured(bmp) else toast("Não foi possível abrir essa imagem")
            } catch (e: Exception) {
                toast("Erro ao carregar a imagem: ${e.localizedMessage ?: "arquivo inválido"}")
            }
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            when { liveRequested -> { liveRequested = false; launchLiveInspection() }
                videoRequested -> { videoRequested = false; launchVideoInspection() }
                else -> launchCameraX() }
        } else { liveRequested = false; videoRequested = false; toast("Permissão da câmera necessária") }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); MainActivityContext.ctx=applicationContext; store=Store(); showHome() }

    private fun showHome(){
        root=base("Central de inspeção")
        val products=store.products()
        val inspections=store.inspections()
        val ok=inspections.count { it.status == Status.OK.name }
        val bad=inspections.count { it.status == Status.BAD.name }
        val attention=inspections.count { it.status == Status.ATTENTION.name }
        val trained=products.sumOf { p -> p.okSamples.size + p.defects.sumOf { it.samples.size } }

        val brand=TextView(this).apply {
            text="QUALIINSP  /  CONTROLE DE QUALIDADE"
            textSize=11f; letterSpacing=.12f
            setTextColor(Color.rgb(0,158,255))
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setPadding(dp(2),dp(4),dp(2),dp(8))
        }
        root.addView(brand)
        val headline=TextView(this).apply {
            text="Inspeção inteligente.\nDecisão com evidências."
            textSize=27f; setTextColor(Color.WHITE)
            typeface=Typeface.create("sans-serif",Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(),1.0f)
            setPadding(dp(2),0,dp(2),dp(10))
        }
        root.addView(headline)
        val subtitle=TextView(this).apply {
            text="Câmera do celular • padrões de referência • rastreabilidade"
            textSize=13f; setTextColor(Color.rgb(143,163,188))
            setPadding(dp(2),0,dp(2),dp(18))
        }
        root.addView(subtitle)

        val hero=Button(this).apply {
            text="▶   INICIAR INSPEÇÃO\nFoto, análise ao vivo ou gravação 360°"
            isAllCaps=false; gravity=Gravity.CENTER_VERTICAL; textSize=16f
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setTextColor(Color.WHITE); background=rounded(Color.rgb(0,75,155),18,Color.rgb(0,158,255))
            setPadding(dp(20),dp(18),dp(16),dp(18)); minHeight=dp(100); stateListAnimator=null
        }
        hero.setOnClickListener { showInspection() }
        root.addView(hero,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })

        addText("RESUMO DAS INSPEÇÕES  •  ${inspections.size} registros\n🟢 Conforme: $ok     🔴 Não conforme: $bad     🟡 Inconclusivo: $attention\nPadrões cadastrados: ${products.size}     Exemplos de treinamento: $trained",14)
        addHomeCardRow("▣  PRODUTOS","Padrões e referências","◈  TREINAMENTO","Ensinar por exemplos",
            {showProducts()},{showProductsForTraining()})
        addHomeCardRow("◷  HISTÓRICO","Rastreabilidade","▤  RELATÓRIOS","Exportar indicadores",
            {showHistory()},{showHistory()})
        button("☷  CHECKLIST DE PRODUÇÃO\nOcorrências, defeitos e liberações"){showProductionChecklist()}
        button("⚙  CRITÉRIOS DE INSPEÇÃO\nRegras, limites e sinalização"){showRules()}
        addText("FLUXO RECOMENDADO\n1. Cadastre o produto e fotografe um padrão aprovado.\n2. Registre exemplos conformes e exemplos de cada defeito.\n3. Use iluminação, distância e enquadramento consistentes.\n4. Revise resultados inconclusivos e valide o método com peças reais antes de liberar produção.",13)
        val footer=TextView(this).apply {
            text="QUALIDADE RASTREÁVEL  •  EVIDÊNCIA ANTES DA DECISÃO"
            textSize=10f; letterSpacing=.05f; gravity=Gravity.CENTER
            setTextColor(Color.rgb(0,145,255)); setPadding(dp(4),dp(12),dp(4),dp(8))
        }
        root.addView(footer)
    }

    private fun addHomeCardRow(t1:String,s1:String,t2:String,s2:String,a1:()->Unit,a2:()->Unit) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        fun card(title:String,sub:String,action:()->Unit):Button {
            return Button(this).apply {
                text="$title\n$sub"; isAllCaps=false; gravity=Gravity.CENTER
                textSize=13f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                setTextColor(Color.rgb(230,240,252))
                background=rounded(Color.rgb(9,15,24),14,Color.rgb(22,62,105))
                setPadding(dp(8),dp(12),dp(8),dp(12)); minHeight=dp(94); stateListAnimator=null
                setOnClickListener { action() }
            }
        }
        row.addView(card(t1,s1,a1),LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
        row.addView(card(t2,s2,a2),LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) })
        root.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
    }

    private fun showProducts(){
        root=base("Produtos e padrões")
        button("＋ Cadastrar produto"){showRegister()}
        val ps=store.products(); if(ps.isEmpty()) addText("Nenhum produto cadastrado.")
        ps.forEach{p->button("${p.code} — ${p.name}\nDefeitos configurados: ${p.defects.size}"){selectedProduct=p;showProductDetail(p)}}
        back()
    }

    private fun showRegister(){
        root=base("Novo produto / padrão aprovado")
        val code=field("Código do produto"); val name=field("Nome do produto"); val color=field("Cor / referência")
        addText("O padrão aprovado deve ser fotografado em condições controladas. A iluminação é parte do padrão.",14)
        val image=ImageView(this); image.layoutParams=LinearLayout.LayoutParams(-1,420); image.scaleType=ImageView.ScaleType.CENTER_INSIDE; root.addView(image)
        var standard:Bitmap?=null
        button("📷 Capturar padrão aprovado"){pendingStandardView={b->standard=b;image.setImageBitmap(b)};captureAction="standard";requestCamera()}
        button("🖼️ Selecionar padrão da galeria / Google Fotos"){pendingStandardView={b->standard=b;image.setImageBitmap(b)};captureAction="standard";chooseGallery()}
        button("Salvar produto"){val c=code.text.toString().trim();val n=name.text.toString().trim();if(c.isEmpty()||n.isEmpty()||standard==null){toast("Informe código, nome e fotografe o padrão");return@button};val p=Product(UUID.randomUUID().toString(),c,n,color.text.toString(),mutableListOf(),mutableListOf());p.standardFile=store.saveBitmap(standard!!,"standard_${p.id}.jpg");p.standardFeatures=ML.features(standard!!);store.saveProduct(p);showProductDetail(p)}
        back()
    }

    private fun showProductDetail(p0:Product){
        val p=store.getProduct(p0.id) ?: p0
        root=base("${p.code} — ${p.name}")
        addText("Padrão: ${if(p.standardFile.isNotEmpty()) "cadastrado" else "não cadastrado"}\nDefeitos configurados: ${p.defects.size}")
        button("＋ Configurar novo defeito"){showDefectEditor(p,null)}
        if(p.defects.isEmpty()) addText("Ainda não há defeitos configurados. Crie um defeito e depois ensine a IA com exemplos reais.")
        p.defects.forEach{d->button("${if(d.critical) "🔴" else "🟡"} ${d.name}\nExemplos: ${d.samples.size} • Tolerância ou Limite: ${d.threshold}"){showDefectEditor(p,d)}}
        button("📷 Ensinar CONFORME (câmera)"){selectedProduct=p;captureAction="train_ok";requestCamera()}
        button("🖼️ Ensinar CONFORME (galeria / Google Fotos)"){selectedProduct=p;captureAction="train_ok";chooseGallery()}
        button("🔎 Inspecionar este produto"){selectedProduct=p;showInspectionFor(p)}
        back()
    }

    private fun showDefectEditor(p:Product, existing:Defect?){
        root=base(if(existing==null)"Novo defeito" else "Configurar defeito")
        val name=field("Nome do defeito (ex.: Rebarba)"); val desc=field("Critério / regra de aceitação"); val threshold=field("Sensibilidade 0.10–1.00 (menor = mais rígida)"); val critical=CheckBox(this);critical.text="Defeito crítico — reprovar automaticamente quando confiança for suficiente";root.addView(critical)
        if(existing!=null){name.setText(existing.name);desc.setText(existing.rule);threshold.setText(existing.threshold.toString());critical.isChecked=existing.critical}
        addText("Exemplos de defeitos do padrão-base: rebarba, ponto de injeção/torpedo, riscos, trincas/falhas, estufamento, rosca, fiapos/películas. Você pode criar outros.",13)
        button("Salvar configuração"){val n=name.text.toString().trim();if(n.isEmpty()){toast("Informe o nome do defeito");return@button};val t=threshold.text.toString().toFloatOrNull()?.coerceIn(0.10f,1f)?:0.35f;val d=existing?:Defect(UUID.randomUUID().toString(),n,"",false,t,mutableListOf());d.name=n;d.rule=desc.text.toString();d.threshold=t;d.critical=critical.isChecked;if(existing==null)p.defects.add(d);store.saveProduct(p);showProductDetail(p)}
        if(existing!=null){
            button("📷 Ensinar este defeito (câmera)"){selectedProduct=p;selectedDefectId=existing.id;captureAction="train_defect";requestCamera()}
            button("🖼️ Ensinar este defeito (galeria / Google Fotos)"){selectedProduct=p;selectedDefectId=existing.id;captureAction="train_defect";chooseGallery()}
        }
        back()
    }

    private fun showProductsForTraining(){root=base("Ensinar IA / configurar defeitos");addText("Escolha o produto. Para cada defeito, capture várias peças reais com o defeito em diferentes posições e intensidades. A mesma tela também permite criar o defeito.");store.products().forEach{p->button("${p.code} — ${p.name} • ${p.defects.sumOf{it.samples.size}} exemplos"){showProductDetail(p)}};back()}

    private fun showInspection(){
        val ps=store.products()
        if(ps.isEmpty()){toast("Cadastre um produto primeiro");showRegister();return}
        root=base("Nova inspeção QualiInsp")
        val spinner=Spinner(this)
        spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,ps.map{"${it.code} — ${it.name}"})
        root.addView(spinner)
        val lot=field("Lote");val machine=field("Máquina / injetora");val mold=field("Molde");val cavity=field("Cavidade");val obs=field("Observação")
        setStatus(Status.WAITING)
        fun prepareInspection(){
            selectedProduct=ps[spinner.selectedItemPosition]
            currentMeta=Meta(lot.text.toString(),machine.text.toString(),mold.text.toString(),cavity.text.toString(),obs.text.toString())
            captureAction="inspection"
        }
        button("📷 Tirar foto: usar a câmera do celular"){prepareInspection();requestCamera()}
        button("🟢🔴🟡 Inspecionar ao vivo pela câmera"){prepareInspection();launchLiveInspection()}
        button("🎥 Gravar produto em 360° e analisar todos os ângulos"){prepareInspection();launchVideoInspection()}
        button("1. 🖼️ Selecionar da galeria"){prepareInspection();chooseGallery()}
        button("2. ☁️ Google Fotos"){prepareInspection();chooseGooglePhotos()}
        addText("3. Analisar foto: depois de escolher ou tirar a imagem, confira a prévia e toque em Analisar foto.",14)
        addText("A inspeção ao vivo compara imagens da câmera com o padrão aprovado e exemplos cadastrados de cada defeito. Critérios textuais servem como referência ao inspetor; não são interpretados automaticamente como medidas. Baixa confiança = avaliar.",14)
        back()
    }

    private fun showInspectionFor(p:Product){
        selectedProduct=p
        root=base("Inspeção — ${p.code}")
        val lot=field("Lote");val machine=field("Máquina / injetora");val mold=field("Molde");val cavity=field("Cavidade")
        setStatus(Status.WAITING)
        fun prepareInspection(){currentMeta=Meta(lot.text.toString(),machine.text.toString(),mold.text.toString(),cavity.text.toString(),"");captureAction="inspection"}
        button("📷 Tirar foto: usar a câmera do celular"){prepareInspection();requestCamera()}
        button("🟢🔴🟡 Inspecionar ao vivo pela câmera"){prepareInspection();launchLiveInspection()}
        button("🎥 Gravar produto em 360° e analisar todos os ângulos"){prepareInspection();launchVideoInspection()}
        button("1. 🖼️ Selecionar da galeria"){prepareInspection();chooseGallery()}
        button("2. ☁️ Google Fotos"){prepareInspection();chooseGooglePhotos()}
        addText("3. Analisar foto: confira a prévia e toque em Analisar foto.",14)
        back()
    }

    private var currentMeta=Meta("","","","","")
    private fun onCaptured(bmp:Bitmap){
        when(captureAction){
            "standard"->{pendingStandardView?.invoke(bmp);pendingStandardView=null;setStatus(Status.WAITING)}
            "train_ok"->{val p=selectedProduct?:return;p.okSamples.add(ML.features(bmp));store.saveProduct(p);toast("Exemplo CONFORME adicionado");showProductDetail(p)}
            "train_defect"->{val p=selectedProduct?:return;val d=p.defects.firstOrNull{it.id==selectedDefectId}?:return;d.samples.add(ML.features(bmp));store.saveProduct(p);toast("Exemplo de ${d.name} adicionado");showProductDetail(p)}
            "checklist_photo"->{checklistPhoto=bmp;checklistPhotoPath=store.saveBitmap(bmp,"checklist_${System.currentTimeMillis()}.jpg");checklistPhotoPreview?.setImageBitmap(bmp);toast("Foto anexada ao registro")}
            else->{val p=selectedProduct?:return;showInspectionPhotoReview(p,bmp)}
        }
    }

    private fun showInspectionPhotoReview(p:Product,bmp:Bitmap){
        root=base("Confirmar foto — ${p.code}")
        addText("Confira se a peça está nítida e bem enquadrada antes de analisar.",14)
        val preview=ImageView(this)
        preview.layoutParams=LinearLayout.LayoutParams(-1,520)
        preview.scaleType=ImageView.ScaleType.FIT_CENTER
        preview.setImageBitmap(bmp)
        root.addView(preview)
        button("3. Analisar foto"){inspect(p,bmp)}
        button("Escolher outra imagem"){showInspectionFor(p)}
        back()
    }

    private fun inspect(p:Product,bmp:Bitmap){
        setStatus(Status.WAITING)
        val now=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(Date())
        val trainedDefects=p.defects.count { it.samples.isNotEmpty() }
        if (p.standardFeatures.isEmpty() || trainedDefects == 0) {
            val missing=buildList {
                if (p.standardFeatures.isEmpty()) add("fotografe um padrão aprovado")
                if (trainedDefects == 0) add("cadastre exemplos reais de pelo menos um defeito")
            }.joinToString(" e ")
            val reason="Resultado inconclusivo: $missing antes de usar a análise. Não é seguro classificar a peça como conforme sem essas evidências."
            store.addInspection(Inspection(now,p.code,p.name,Status.ATTENTION.name,reason,0f,currentMeta.lot,currentMeta.machine,currentMeta.mold,currentMeta.cavity))
            resultDialog(Status.ATTENTION,"Evidências insuficientes",reason)
            return
        }
        val productScore=ML.productSimilarity(p,bmp)
        if(productScore < 0.45f){
            val reason="Produto não identificado com segurança. Similaridade visual preliminar: ${fmt(productScore)}. Confira código, enquadramento e iluminação."
            store.addInspection(Inspection(now,p.code,p.name,Status.ATTENTION.name,reason,productScore,currentMeta.lot,currentMeta.machine,currentMeta.mold,currentMeta.cavity))
            resultDialog(Status.ATTENTION,"Resultado inconclusivo",reason)
            return
        }
        val findings=ML.findDefects(p,bmp)
        val critical=findings.filter{it.confidence>=it.threshold&&p.defects.firstOrNull{d->d.id==it.id}?.critical==true}
        val probable=findings.filter{it.confidence>=it.threshold}
        val borderline=findings.filter{it.confidence>=it.threshold*0.75f}
        val status=when {
            critical.isNotEmpty() || probable.isNotEmpty() -> Status.BAD
            productScore < 0.60f || borderline.isNotEmpty() -> Status.ATTENTION
            else -> Status.OK
        }
        val reason=when {
            critical.isNotEmpty() -> "Produto não conforme: defeito crítico sinalizado — ${critical.joinToString { it.name }}."
            probable.isNotEmpty() -> "Produto não conforme: possível ${probable.joinToString { it.name }}. Confirme visualmente a evidência."
            status==Status.OK -> "Produto sem defeitos cadastrados reconhecidos pelo comparador visual. A conformidade é preliminar e depende de validação do processo."
            else -> "Resultado inconclusivo: a evidência visual está próxima do limite de decisão. Reinspecione a peça."
        }
        store.addInspection(Inspection(now,p.code,p.name,status.name,reason,productScore,currentMeta.lot,currentMeta.machine,currentMeta.mold,currentMeta.cavity))
        resultDialog(status,when(status){Status.OK->"Resultado: CONFORME (preliminar)";Status.BAD->"Resultado: NÃO CONFORME";else->"Resultado: INCONCLUSIVO"},reason+"\n\nSimilaridade visual: ${fmt(productScore)}\n\nEste aplicativo usa comparação visual experimental, não um sistema de visão industrial certificado. Dimensões, peso, rosca, defeitos internos e tolerâncias exigem instrumentos ou modelos validados. Não use o resultado isoladamente para liberar lotes críticos.")
    }

    private fun resultDialog(status:Status,title:String,msg:String){setStatus(status);AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK"){_,_->showHome()}.setCancelable(false).show()}
    private fun fmt(v:Float)=String.format(Locale.US,"%.1f%%",v.coerceIn(0f,1f)*100f)

    private val productionDefects = listOf(
        "Rebarba", "Chupagem", "Deformação", "Torpedo alto", "Fiapo", "Concavidade",
        "Cor fora do padrão", "Falha no produto", "Filete da rosca do produto amassada",
        "Tampa pulando no teste no pote (não trava ao rosquear o produto)"
    )
    private val productionEvents = listOf(
        "Conforme", "Não Conforme", "Máquina Parada", "Troca de Molde",
        "Manutenção em Geral", "PCP em Geral", "Teste de Molde", "Teste de Qualidade", "Aguardando Maquininha"
    )
    private val machineStates = mutableMapOf<Int, String>()
    private var checklistMachine = 1
    private var checklistPhoto: Bitmap? = null
    private var checklistPhotoPath: String = ""
    private var checklistPhotoPreview: ImageView? = null
    private var checklistEvent: String = "Conforme"

    private fun showProductionChecklist() {
        // Recarrega o último estado salvo de cada máquina a partir do histórico persistente.
        for (machineNo in 1..24) {
            val last = store.inspections().lastOrNull { it.machine == "Máquina $machineNo" && it.reason.contains("Checklist de produção") }
            if (last != null) {
                val savedState = Regex("Estado: ([^\\n]+)").find(last.reason)?.groupValues?.getOrNull(1)
                if (!savedState.isNullOrBlank()) machineStates[machineNo] = savedState
            }
        }
        root = base("Checklist de produção • 24 máquinas")
        addText("Selecione uma máquina para registrar seu estado. Cada máquina recebe exatamente um estado por registro. A não conformidade exige foto, defeito identificado e avaliação da liberação.", 14)
        val counts = productionEvents.associateWith { event -> machineStates.values.count { it == event } }
        addText("Resumo atual: ${machineStates.size}/24 máquinas preenchidas\n" + productionEvents.filter { (counts[it] ?: 0) > 0 }.joinToString("  •  ") { "$it: ${counts[it]}" }, 13)
        for (machineNo in 1..24) {
            val state = machineStates[machineNo] ?: "Pendente"
            val statusColor = when (state) {
                "Conforme" -> Color.rgb(25, 150, 80)
                "Não Conforme" -> Color.rgb(190, 45, 55)
                "Pendente" -> Color.rgb(70, 85, 105)
                else -> Color.rgb(175, 125, 20)
            }
            val card = Button(this).apply {
                text = "MÁQUINA $machineNo\n$state  •  ${if (state == "Pendente") "Toque para preencher" else "Toque para alterar/registrar"}"
                isAllCaps = false; textSize = 15f; gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE); background = rounded(Color.rgb(13,19,30), 14, statusColor)
                setPadding(dp(16), dp(12), dp(12), dp(12)); stateListAnimator = null
                setOnClickListener { showMachineChecklist(machineNo) }
            }
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        button("Atualizar resumo") { showProductionChecklist() }
        back()
    }

    private fun showMachineChecklist(machineNo: Int) {
        checklistMachine = machineNo
        checklistPhoto = null
        checklistPhotoPath = ""
        checklistPhotoPreview = null
        checklistEvent = machineStates[machineNo] ?: "Conforme"
        root = base("Checklist • Máquina $machineNo")
        addText("Selecione somente UM estado para esta máquina. O estado escolhido fica destacado e substitui o anterior.", 14)
        var selectedEvent = machineStates[machineNo] ?: ""
        val eventButtons = mutableMapOf<String, Button>()
        fun paintEventButtons() {
            eventButtons.forEach { (name, b) ->
                val selected = name == selectedEvent
                val color = when (name) {
                    "Conforme" -> Color.rgb(20, 125, 65)
                    "Não Conforme" -> Color.rgb(165, 35, 45)
                    else -> Color.rgb(18, 55, 90)
                }
                b.background = rounded(if (selected) color else Color.rgb(13,19,30), 12,
                    if (selected) Color.rgb(0,158,255) else Color.rgb(35,70,105))
            }
        }
        productionEvents.forEach { event ->
            val b = Button(this).apply {
                text = if (event == selectedEvent) "✓  $event" else event
                isAllCaps = false; textSize = 14f; setTextColor(Color.WHITE)
                background = rounded(Color.rgb(13,19,30), 12, Color.rgb(35,70,105))
                setPadding(dp(12), dp(8), dp(12), dp(8)); stateListAnimator = null
                setOnClickListener {
                    selectedEvent = event
                    checklistEvent = event
                    eventButtons.forEach { (name, btn) -> btn.text = if (name == selectedEvent) "✓  $name" else name }
                    paintEventButtons()
                    // Reabrir a tela ao selecionar Não Conforme para mostrar os campos condicionais.
                    showMachineChecklistWithEvent(machineNo, selectedEvent)
                }
            }
            eventButtons[event] = b
            root.addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        paintEventButtons()
        addText("Identificação do registro", 16)
        val code = field("Código / referência do produto")
        val product = field("Nome do produto")
        val lot = field("Lote")
        val mold = field("Molde")
        val operator = field("Operador do turno (nome)")
        button("Salvar estado da máquina") {
            if (selectedEvent.isBlank()) { toast("Selecione um estado para a Máquina $machineNo"); return@button }
            if (selectedEvent == "Não Conforme") {
                showMachineChecklistWithEvent(machineNo, selectedEvent)
            } else {
                machineStates[machineNo] = selectedEvent
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                val reason = "Checklist de produção — Máquina $machineNo\nEstado: $selectedEvent\nMolde: ${mold.text}\nOperador: ${operator.text}"
                store.addInspection(Inspection(now, code.text.toString(), product.text.toString(), if (selectedEvent == "Conforme") Status.OK.name else Status.ATTENTION.name, reason, 0f, lot.text.toString(), "Máquina $machineNo", mold.text.toString(), ""))
                toast("Estado da Máquina $machineNo salvo")
                showProductionChecklist()
            }
        }
        backToMachines()
    }

    private fun showMachineChecklistWithEvent(machineNo: Int, initialEvent: String) {
        checklistMachine = machineNo
        checklistEvent = initialEvent
        checklistPhoto = null
        checklistPhotoPath = ""
        checklistPhotoPreview = null
        root = base("Máquina $machineNo • ${if (initialEvent == "Não Conforme") "Não conformidade" else "Checklist"}")
        addText("Estado da máquina: $initialEvent", 16)
        val code = field("Código / referência do produto")
        val product = field("Nome do produto")
        val lot = field("Lote")
        val mold = field("Molde")
        val operator = field("Operador do turno")
        val selectedDefects = mutableListOf<String>()
        if (initialEvent == "Não Conforme") {
            addText("1ª etapa — registre evidências. A foto do defeito é obrigatória.", 15)
            addText("Marque todos os defeitos encontrados", 16)
            productionDefects.forEach { defect ->
                val cb = CheckBox(this).apply { text = defect; setTextColor(Color.rgb(230,240,252)); textSize = 14f }
                root.addView(cb)
                cb.setOnCheckedChangeListener { _, checked -> if (checked) { if (!selectedDefects.contains(defect)) selectedDefects.add(defect) } else selectedDefects.remove(defect) }
            }
            val photoPreview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(210))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(13,19,30))
            }
            checklistPhotoPreview = photoPreview
            root.addView(photoPreview, LinearLayout.LayoutParams(-1, dp(210)).apply { bottomMargin = dp(8) })
            button("📷 Fotografar defeito (obrigatório)") { captureAction = "checklist_photo"; requestCamera() }
            button("🖼️ Anexar imagem da galeria") { captureAction = "checklist_photo"; chooseGallery() }
        }
        addText("2ª etapa — controles de produção e qualidade", 16)
        fun choice(label: String, options: List<String>): Spinner {
            addText(label, 14)
            val sp = Spinner(this)
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
            root.addView(sp, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            return sp
        }
        val ofRelease = choice("Existe liberação de máquina na OF?", listOf("Selecione", "SIM", "NÃO", "QUALIDADE"))
        val piv = choice("Tem PIV na máquina?", listOf("Selecione", "SIM", "NÃO", "N/A"))
        val standard = choice("Padrão disponível e atualizado?", listOf("Selecione", "SIM", "NÃO", "N/A"))
        val testProduct = choice("Produto para teste disponível?", listOf("Selecione", "SIM", "NÃO", "N/A"))
        val hourly = choice("Inspeção de hora em hora realizada?", listOf("Selecione", "SIM", "NÃO", "PENDENTE"))
        val checklist = choice("Checklist da máquina preenchido?", listOf("Selecione", "SIM", "NÃO", "PENDENTE"))
        val conditionalRelease = CheckBox(this).apply {
            text = "Solicitar LIBERAÇÃO CONDICIONAL do produto com defeito pelo líder/preparador"
            setTextColor(Color.rgb(255,205,90)); textSize = 15f
        }
        root.addView(conditionalRelease, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val releaseReason = field("Justificativa / limite da liberação condicional")
        val leader = field("Nome do líder ou preparador do turno")
        addText("Assinatura manuscrita digital do líder/preparador — assinar no quadro abaixo. A assinatura é registrada como evidência do aplicativo; siga também o procedimento formal da empresa.", 13)
        val signature = SignaturePad(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(170))
            background = rounded(Color.WHITE, 10, Color.rgb(0,145,255))
        }
        root.addView(signature, LinearLayout.LayoutParams(-1, dp(170)).apply { bottomMargin = dp(8) })
        button("Limpar assinatura") { signature.clear() }
        button("Salvar checklist da Máquina $machineNo") {
            if (initialEvent == "Não Conforme") {
                if (selectedDefects.isEmpty()) { toast("Marque pelo menos um defeito encontrado"); return@button }
                if (checklistPhoto == null || checklistPhotoPath.isBlank()) { toast("A foto do defeito é obrigatória"); return@button }
            }
            val controls = listOf(ofRelease, piv, standard, testProduct, hourly, checklist)
            if (controls.any { it.selectedItemPosition == 0 }) { toast("Responda todas as perguntas da 2ª etapa"); return@button }
            if (conditionalRelease.isChecked) {
                if (initialEvent != "Não Conforme") { toast("Liberação condicional só se aplica a produto não conforme"); return@button }
                if (leader.text.isBlank() || releaseReason.text.isBlank()) { toast("Informe o líder/preparador e a justificativa da liberação"); return@button }
                if (!signature.hasSignature()) { toast("O líder/preparador deve assinar no quadro antes de liberar"); return@button }
            }
            val signaturePath = if (conditionalRelease.isChecked) {
                val bmp = signature.exportBitmap()
                store.saveBitmap(bmp, "assinatura_lider_maquina_${machineNo}_${System.currentTimeMillis()}.jpg")
            } else ""
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val reason = buildString {
                append("Checklist de produção — Máquina ").append(machineNo)
                append("\nEstado: ").append(initialEvent)
                if (selectedDefects.isNotEmpty()) append("\nDefeitos: ").append(selectedDefects.joinToString())
                append("\nOF liberada: ").append(ofRelease.selectedItem)
                append("\nPIV na máquina: ").append(piv.selectedItem)
                append("\nPadrão: ").append(standard.selectedItem)
                append("\nProduto para teste: ").append(testProduct.selectedItem)
                append("\nInspeção hora em hora: ").append(hourly.selectedItem)
                append("\nChecklist: ").append(checklist.selectedItem)
                append("\nOperador: ").append(operator.text)
                append("\nMolde: ").append(mold.text)
                if (checklistPhotoPath.isNotBlank()) append("\nFoto do defeito: ").append(checklistPhotoPath)
                if (conditionalRelease.isChecked) {
                    append("\nLIBERAÇÃO CONDICIONAL: SIM")
                    append("\nAutorizador líder/preparador: ").append(leader.text)
                    append("\nJustificativa: ").append(releaseReason.text)
                    append("\nAssinatura digital salva: ").append(signaturePath)
                } else append("\nLiberação condicional: NÃO")
            }
            val status = if (initialEvent == "Conforme") Status.OK else if (initialEvent == "Não Conforme") Status.BAD else Status.ATTENTION
            store.addInspection(Inspection(now, code.text.toString(), product.text.toString(), status.name, reason, 0f, lot.text.toString(), "Máquina $machineNo", mold.text.toString(), ""))
            machineStates[machineNo] = initialEvent
            toast("Checklist da Máquina $machineNo salvo no histórico")
            showProductionChecklist()
        }
        backToMachines()
    }

    private fun backToMachines() { button("← Voltar às 24 máquinas") { showProductionChecklist() } }

    private fun showHistory(){root=base("Histórico de inspeções");val xs=store.inspections().takeLast(100).reversed();if(xs.isEmpty())addText("Nenhuma inspeção registrada.");xs.forEach{addText("${it.date} | ${it.code} | ${it.status}\n${it.reason}\nProduto: ${it.name}\nLote: ${it.lot} • Máquina: ${it.machine}\n")};button("Compartilhar relatório"){share(store.export())};back()}
    
private fun showRules() {
    root = base("Regras e sinalização")

    addText(
        "🔵 AGUARDANDO: câmera pronta / aguardando captura.\n" +
        "🟢 CONFORME: o produto foi identificado e passou nos testes sem apresentar defeitos.\n" +
        "🔴 NÃO CONFORME: o defeito configurado atingiu o nível crítico de tolerância.\n" +
        "🟡 ATENÇÃO NECESSÁRIA: este produto apresenta baixa confiança e requer avaliação para confirmar sua conformidade."
    )

    addText(
        "Os limites são configuráveis por defeito. A IA deve ser calibrada com amostras reais, iluminação e posição controladas. O documento-base também determina que a câmera não seja tratada como substituta automática de paquímetro, micrômetro, balança ou teste de encaixe sem validação específica.",
        14
    )

    back()
}


    private fun launchLiveInspection(){
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){liveRequested=true;permission.launch(Manifest.permission.CAMERA);return}
        val p=selectedProduct ?: run { toast("Selecione um produto"); return }
        val model=JSONObject().apply {
            put("standard", JSONArray(p.standardFeatures))
            put("okSamples", JSONArray().apply { p.okSamples.forEach { put(JSONArray(it)) } })
            put("defects", JSONArray().apply { p.defects.forEach { d -> put(JSONObject().apply {
                put("name", d.name); put("rule", d.rule); put("critical", d.critical); put("threshold", d.threshold)
                put("samples", JSONArray().apply { d.samples.forEach { put(JSONArray(it)) } })
            }) } })
        }.toString()
        capture.launch(Intent(this, CameraXCaptureActivity::class.java)
            .putExtra(CameraXCaptureActivity.EXTRA_LIVE_MODE, true)
            .putExtra(CameraXCaptureActivity.EXTRA_LIVE_MODEL, model))
    }

    private fun launchVideoInspection() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            videoRequested = true
            permission.launch(Manifest.permission.CAMERA)
            return
        }
        if (selectedProduct == null) { toast("Selecione um produto antes de gravar"); return }
        capture.launch(Intent(this, CameraXCaptureActivity::class.java)
            .putExtra(CameraXCaptureActivity.EXTRA_VIDEO_MODE, true))
    }

    private fun analyzeRecordedVideo(p: Product, path: String) {
        setStatus(Status.WAITING)
        toast("Analisando quadros do vídeo. Aguarde...")
        Thread {
            val retriever = MediaMetadataRetriever()
            var frameCount = 0
            var maxSimilarity = 0f
            var minSimilarity = 1f
            val defectHits = linkedMapOf<String, Int>()
            val defectNames = linkedMapOf<String, String>()
            var decodeErrors = false
            try {
                retriever.setDataSource(path)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (durationMs <= 0L) throw IllegalStateException("O vídeo está vazio ou não pôde ser lido")
                // Captura até 30 quadros distribuídos ao longo do vídeo para representar diferentes ângulos.
                val sampleCount = ((durationMs / 700L).toInt() + 1).coerceIn(1, 30)
                for (i in 0 until sampleCount) {
                    val timeMs = if (sampleCount == 1) 0L else (i * durationMs / sampleCount)
                    val bmp = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp == null) { decodeErrors = true; continue }
                    try {
                        val score = ML.productSimilarity(p, bmp)
                        maxSimilarity = maxOf(maxSimilarity, score)
                        minSimilarity = minOf(minSimilarity, score)
                        val findings = ML.findDefects(p, bmp).filter { it.confidence >= it.threshold }
                        findings.forEach { finding ->
                            defectHits[finding.id] = (defectHits[finding.id] ?: 0) + 1
                            defectNames[finding.id] = finding.name
                        }
                        frameCount++
                    } finally { bmp.recycle() }
                }
            } catch (e: Exception) {
                runOnUiThread { setStatus(Status.ATTENTION); toast("Não foi possível analisar o vídeo: ${e.localizedMessage ?: "arquivo inválido"}") }
                return@Thread
            } finally { try { retriever.release() } catch (_: Exception) {} }
            val finalStatus: Status
            val reason: String
            when {
                frameCount < 3 -> { finalStatus = Status.ATTENTION; reason = "Vídeo insuficiente: foram analisados apenas $frameCount quadros. Grave novamente mostrando vários ângulos." }
                maxSimilarity < 0.45f -> { finalStatus = Status.ATTENTION; reason = "Não foi possível identificar o produto com segurança nos quadros analisados." }
                defectHits.isNotEmpty() -> {
                    finalStatus = Status.BAD
                    val names = defectHits.keys.mapNotNull { defectNames[it] }.distinct()
                    reason = "Possível não conformidade em ${defectHits.values.sum()} quadro(s): ${names.joinToString()}. Revise as imagens/peça antes da decisão final."
                }
                decodeErrors || minSimilarity < 0.60f -> { finalStatus = Status.ATTENTION; reason = "Análise inconclusiva: alguns quadros não foram lidos ou variaram muito do padrão. Reinspecione os ângulos indicados." }
                else -> { finalStatus = Status.OK; reason = "Nenhum defeito cadastrado foi reconhecido nos $frameCount quadros analisados. Isso não garante ausência de defeitos não treinados." }
            }
            val score = if (frameCount > 0) maxSimilarity else 0f
            runOnUiThread {
                val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                store.addInspection(Inspection(now, p.code, p.name, finalStatus.name, "Análise por vídeo: $reason", score, currentMeta.lot, currentMeta.machine, currentMeta.mold, currentMeta.cavity))
                resultDialog(finalStatus, "Resultado da análise por vídeo", "$reason\n\nQuadros analisados: $frameCount\nMaior similaridade com o padrão: ${fmt(score)}\n\nA gravação é amostrada em quadros; defeitos muito pequenos, internos ou não cadastrados podem passar despercebidos. Confirme os casos críticos com o método de controle apropriado.")
                try { File(path).delete() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun requestCamera(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.CAMERA)else launchCameraX()}
    private fun launchCameraX(){capture.launch(Intent(this, CameraXCaptureActivity::class.java))}
    private fun chooseGallery(){gallery.launch("image/*")}
    private fun chooseGooglePhotos(){photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
    private fun dp(value:Int)= (value * resources.displayMetrics.density).toInt()
    private fun rounded(color:Int, radius:Int=16, strokeColor:Int?=null): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius=dp(radius).toFloat()
        if(strokeColor!=null) setStroke(dp(1),strokeColor)
    }
    private fun base(title:String):LinearLayout {
        val scroller=ScrollView(this).apply { setBackgroundColor(Color.rgb(5,8,14)); clipToPadding=false }
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(20),dp(20),dp(28))
            setBackgroundColor(Color.rgb(5,8,14))
        }
        scroller.addView(root, android.view.ViewGroup.LayoutParams(-1, -2))
        setContentView(scroller)
        addTitle(title)
        return root
    }
    private fun addTitle(t:String) {
        val eyebrow=TextView(this).apply {
            text="QUALIDADE INDUSTRIAL  /  INSPETOR IA"
            textSize=11f; letterSpacing=.12f; setTextColor(Color.rgb(0,145,255)); typeface=Typeface.DEFAULT_BOLD
            setPadding(dp(2),dp(2),0,dp(7))
        }
        root.addView(eyebrow)
        val v=TextView(this).apply {
            text=t; textSize=25f; setTextColor(Color.rgb(240,246,255)); typeface=Typeface.create("sans-serif",Typeface.BOLD)
            setPadding(dp(1),0,0,dp(18))
        }
        root.addView(v)
        val line=View(this).apply { setBackgroundColor(Color.rgb(0,119,255)) }
        root.addView(line,LinearLayout.LayoutParams(dp(54),dp(4)).apply { bottomMargin=dp(16) })
    }
    private fun addText(t:String,size:Int=16) {
        val v=TextView(this).apply {
            text=t; textSize=size.toFloat(); setTextColor(Color.rgb(205,216,232)); setLineSpacing(dp(2).toFloat(),1.02f)
            setPadding(dp(15),dp(13),dp(15),dp(13)); background=rounded(Color.rgb(13,19,30),14,Color.rgb(25,61,99))
        }
        root.addView(v,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
    }
    private fun field(h:String):EditText {
        val e=EditText(this).apply {
            hint=h; textSize=15f; setTextColor(Color.rgb(240,246,255)); setHintTextColor(Color.rgb(128,148,174))
            setPadding(dp(14),dp(12),dp(14),dp(12)); background=rounded(Color.rgb(10,15,24),12,Color.rgb(30,75,120))
            minHeight=dp(52)
        }
        root.addView(e,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        return e
    }
    private fun button(t:String,a:()->Unit) {
        val primary = t.contains("Nova inspeção") || t.contains("Analisar foto") || t.contains("Salvar")
        val b=Button(this).apply {
            text=t; isAllCaps=false; textSize=15f; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setTextColor(Color.WHITE)
            background=rounded(if(primary) Color.rgb(0,86,190) else Color.rgb(13,19,30),14,if(primary) Color.rgb(0,145,255) else Color.rgb(30,75,120))
            setPadding(dp(16),dp(12),dp(16),dp(12)); minHeight=dp(54); stateListAnimator=null
            elevation=dp(1).toFloat()
        }
        b.setOnClickListener{a()}
        root.addView(b,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
    }
    private fun back(){button("← Voltar ao início"){showHome()}}
    private fun toast(t:String){Toast.makeText(this,t,Toast.LENGTH_LONG).show()}
    private fun share(t:String){startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,t)},"Compartilhar relatório"))}
    private fun setStatus(s:Status) {
        if(!::root.isInitialized)return
        if(!::beacon.isInitialized||beacon.parent!=root){
            beacon=TextView(this).apply { gravity=Gravity.CENTER; textSize=15f; typeface=Typeface.DEFAULT_BOLD; setPadding(dp(16),dp(17),dp(16),dp(17)) }
            root.addView(beacon,0,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })
        }
        blinkOn=false; handler.removeCallbacksAndMessages(null)
        beacon.text=when(s){Status.WAITING->"●  AGUARDANDO CAPTURA";Status.OK->"●  CONFORME";Status.BAD->"●  NÃO CONFORME";Status.ATTENTION->"●  AVALIAR / ATENÇÃO"}
        val color=when(s){Status.WAITING->Color.rgb(16,47,80);Status.OK->Color.rgb(27,128,83);Status.BAD->Color.rgb(178,49,57);Status.ATTENTION->Color.rgb(173,111,10)}
        beacon.setTextColor(when(s){Status.WAITING->Color.rgb(100,190,255);Status.OK->Color.rgb(65,230,120);Status.BAD->Color.rgb(255,75,85);Status.ATTENTION->Color.rgb(255,190,55)}); beacon.background=rounded(Color.rgb(13,19,30),14,color)
        if(s==Status.WAITING){val r=object:Runnable{override fun run(){blinkOn=!blinkOn;beacon.alpha=if(blinkOn)1f else .72f;handler.postDelayed(this,900)}};handler.post(r)}
    }
}

enum class Status{WAITING,OK,BAD,ATTENTION}
class SignaturePad(context: android.content.Context) : View(context) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var drawn = false
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); canvas.drawColor(Color.WHITE); canvas.drawPath(path, paint) }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { path.moveTo(event.x, event.y); drawn = true; invalidate(); return true }
            MotionEvent.ACTION_MOVE -> { path.lineTo(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_UP -> { path.lineTo(event.x, event.y); invalidate(); performClick(); return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun clear() { path.reset(); drawn = false; invalidate() }
    fun hasSignature(): Boolean = drawn
    fun exportBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp); canvas.drawColor(Color.WHITE); canvas.drawPath(path, paint); return bmp
    }
}

data class Product(val id:String,val code:String,val name:String,val color:String,val defects:MutableList<Defect>,val okSamples:MutableList<List<Float>>,var standardFile:String="",var standardFeatures:List<Float> = emptyList())
data class Defect(val id:String,var name:String,var rule:String,var critical:Boolean,var threshold:Float,val samples:MutableList<List<Float>>)
data class Finding(val id:String,val name:String,val confidence:Float,val threshold:Float)
data class Inspection(val date:String,val code:String,val name:String,val status:String,val reason:String,val productScore:Float,val lot:String,val machine:String,val mold:String,val cavity:String)
data class Meta(val lot:String,val machine:String,val mold:String,val cavity:String,val obs:String)

class Store(private val a:android.content.Context=MainActivityContext.ctx){
 private val sp=a.getSharedPreferences("INSPETOR IA_ml_v3",0)
 fun products():MutableList<Product>{val arr=JSONArray(sp.getString("products","[]"));val out=mutableListOf<Product>();for(i in 0 until arr.length()){val o=arr.getJSONObject(i);val ds=mutableListOf<Defect>();val da=o.optJSONArray("defects")?:JSONArray();for(j in 0 until da.length()){val d=da.getJSONObject(j);val ss=mutableListOf<List<Float>>();val sa=d.optJSONArray("samples")?:JSONArray();for(k in 0 until sa.length())ss.add(readFeatures(sa.getJSONArray(k)));ds.add(Defect(d.getString("id"),d.getString("name"),d.optString("rule"),d.optBoolean("critical"),d.optDouble("threshold",.35).toFloat(),ss))};val oks=mutableListOf<List<Float>>();val oa=o.optJSONArray("okSamples")?:JSONArray();for(j in 0 until oa.length())oks.add(readFeatures(oa.getJSONArray(j)));out.add(Product(o.getString("id"),o.getString("code"),o.getString("name"),o.optString("color"),ds,oks,o.optString("standardFile"),readFeaturesNullable(o.optJSONArray("standardFeatures"))))};return out}
 private fun readFeatures(a:JSONArray):List<Float>{val l=mutableListOf<Float>();for(i in 0 until a.length())l.add(a.getDouble(i).toFloat());return l}
 private fun readFeaturesNullable(a:JSONArray?):List<Float>{return if(a==null)emptyList() else readFeatures(a)}
 fun getProduct(id:String)=products().firstOrNull{it.id==id}
 fun saveProduct(p: Product) {
  val all = products().filter { it.id != p.id }.toMutableList()
  all.add(p)
  val arr = JSONArray()
  all.forEach { product ->
   val obj = JSONObject().apply {
    put("id", product.id)
    put("code", product.code)
    put("name", product.name)
    put("color", product.color)
    put("standardFile", product.standardFile)
    put("standardFeatures", JSONArray(product.standardFeatures))
    put("okSamples", JSONArray().apply {
     product.okSamples.forEach { sample -> put(JSONArray(sample)) }
    })
    put("defects", JSONArray().apply {
     product.defects.forEach { defect ->
      put(JSONObject().apply {
       put("id", defect.id)
       put("name", defect.name)
       put("rule", defect.rule)
       put("critical", defect.critical)
       put("threshold", defect.threshold)
       put("samples", JSONArray().apply {
        defect.samples.forEach { sample -> put(JSONArray(sample)) }
       })
      })
     }
    })
   }
   arr.put(obj)
  }
  sp.edit().putString("products", arr.toString()).apply()
 }
 fun addInspection(x:Inspection){val a=JSONArray(sp.getString("inspections","[]"));a.put(JSONObject().apply{put("date",x.date);put("code",x.code);put("name",x.name);put("status",x.status);put("reason",x.reason);put("score",x.productScore);put("lot",x.lot);put("machine",x.machine);put("mold",x.mold);put("cavity",x.cavity)});sp.edit().putString("inspections",a.toString()).apply()}
 fun inspections():List<Inspection>{val a=JSONArray(sp.getString("inspections","[]"));val o=mutableListOf<Inspection>();for(i in 0 until a.length()){val x=a.getJSONObject(i);o.add(Inspection(x.getString("date"),x.getString("code"),x.getString("name"),x.getString("status"),x.getString("reason"),x.optDouble("score",0.0).toFloat(),x.optString("lot"),x.optString("machine"),x.optString("mold"),x.optString("cavity")))};return o}
 fun saveBitmap(b:Bitmap,name:String):String{val f=File(a.filesDir,name);f.outputStream().use{b.compress(Bitmap.CompressFormat.JPEG,92,it)};return f.name}
 fun export()=buildString{appendLine("INSPETOR IA • DARK PREMIUM");inspections().forEach{appendLine("${it.date};${it.code};${it.name};${it.status};${it.reason};${it.lot};${it.machine};${it.mold};${it.cavity}")}}
}

object ML{
 fun features(src:Bitmap):List<Float>{val b=Bitmap.createScaledBitmap(src,64,64,true);val h=FloatArray(48);var lum=0f;var lum2=0f;var edge=0f;for(y in 0 until 64)for(x in 0 until 64){val c=b.getPixel(x,y);val r=Color.red(c)/255f;val g=Color.green(c)/255f;val bl=Color.blue(c)/255f;h[(r*15).toInt().coerceIn(0,15)]++;h[16+(g*15).toInt().coerceIn(0,15)]++;h[32+(bl*15).toInt().coerceIn(0,15)]++;val l=.299f*r+.587f*g+.114f*bl;lum+=l;lum2+=l*l;if(x>0){val q=b.getPixel(x-1,y);val pl=.299f*Color.red(q)/255f+.587f*Color.green(q)/255f+.114f*Color.blue(q)/255f;edge+=kotlin.math.abs(l-pl)}};val n=4096f;for(i in h.indices)h[i]/=n;return h.toList()+listOf(lum/n,lum2/n,edge/n,Color.red(b.getPixel(32,32))/255f,Color.green(b.getPixel(32,32))/255f,Color.blue(b.getPixel(32,32))/255f)}
 private fun distance(a:List<Float>,b:List<Float>):Float{
   if(a.size!=b.size||a.isEmpty()||b.isEmpty()) return 1f
   var sum=0f
   for(i in a.indices){val delta=a[i]-b[i];sum+=delta*delta}
   // Normaliza a distância para [0,1], para que possa ser convertida em similaridade.
   return (sqrt(sum.toDouble()).toFloat()/3.5f).coerceIn(0f,1f)
 }
 fun productSimilarity(p:Product,b:Bitmap):Float{val f=features(b);val refs=mutableListOf<List<Float>>();if(p.standardFeatures.isNotEmpty())refs.add(p.standardFeatures);refs.addAll(p.okSamples);if(refs.isEmpty())return 0f;return (1f-refs.minOf{sample -> distance(f,sample)}).coerceIn(0f,1f)}
 fun findDefects(p:Product,b:Bitmap):List<Finding>{val f=features(b);return p.defects.mapNotNull{d->if(d.samples.isEmpty())null else {val dist=d.samples.minOf{sample -> distance(f,sample)};Finding(d.id,d.name,(1f-dist).coerceIn(0f,1f),d.threshold)}}}
}
object MainActivityContext{lateinit var ctx:android.content.Context}
