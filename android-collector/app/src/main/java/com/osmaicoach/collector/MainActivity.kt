package com.osmaicoach.collector

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

class MainActivity:ComponentActivity(){
    private lateinit var repo:SessionRepository

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        repo=SessionRepository(this)
        setContent{CoachTheme{NativeCoachApp()}}
    }

    @Composable
    private fun NativeCoachApp(){
        var tab by remember{mutableStateOf(0)}
        var refresh by remember{mutableIntStateOf(0)}
        DisposableEffect(Unit){
            val l:()->Unit={runOnUiThread{refresh++}}
            CollectorState.addListener(l)
            onDispose{CollectorState.removeListener(l)}
        }
        LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(1500);refresh++}}

        val serviceReady=isAccessibilityServiceEnabled()
        val recording=CollectorState.isRecording()
        val latest=repo.latestSession()
        val sessions=repo.listSessions()

        Scaffold(
            containerColor=Color(0xFFF6F7F2),
            topBar={
                Surface(color=Color(0xFF101E27),shadowElevation=4.dp){
                    Column(Modifier.fillMaxWidth().padding(18.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Surface(shape=RoundedCornerShape(12.dp),color=Color(0xFFB8E34D)){
                                Icon(Icons.Default.SportsSoccer,null,Modifier.padding(10.dp),tint=Color(0xFF101E27))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column{
                                Text("OSM AI Coach Pro",color=Color.White,fontWeight=FontWeight.Bold,fontSize=20.sp)
                                Text("Coach nativo • leitura contínua",color=Color(0xFFAAC0CC),fontSize=12.sp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Icon(
                                if(serviceReady)Icons.Default.CheckCircle else Icons.Default.Warning,
                                null,tint=if(serviceReady)Color(0xFFB8E34D) else Color(0xFFFFC857)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when{
                                    !serviceReady->"Leitura automática desativada"
                                    recording->"Lendo o OSM agora"
                                    else->"Leitura automática pronta"
                                },
                                color=Color.White,fontWeight=FontWeight.SemiBold
                            )
                        }
                        if(!serviceReady){
                            Spacer(Modifier.height(10.dp))
                            Button(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){
                                Text("Ativar uma vez")
                            }
                        }
                    }
                }
            },
            bottomBar={
                NavigationBar(containerColor=Color(0xFF101E27)){
                    val labels=listOf("Hoje","Sessões","Slots","Diretor","Ajustes")
                    val icons=listOf(Icons.Default.Home,Icons.Default.Route,Icons.Default.Dashboard,Icons.Default.TrendingUp,Icons.Default.Settings)
                    labels.forEachIndexed{i,label->
                        NavigationBarItem(
                            selected=tab==i,onClick={tab=i},
                            icon={Icon(icons[i],null)},
                            label={Text(label)},
                            colors=NavigationBarItemDefaults.colors(
                                selectedIconColor=Color(0xFFB8E34D),
                                selectedTextColor=Color(0xFFB8E34D),
                                unselectedIconColor=Color(0xFF9FB0B8),
                                unselectedTextColor=Color(0xFF9FB0B8),
                                indicatorColor=Color(0xFF21323C)
                            )
                        )
                    }
                }
            }
        ){pad->
            Box(Modifier.padding(pad).fillMaxSize()){
                when(tab){
                    0->TodayScreen(serviceReady,recording,latest){openOsm()}
                    1->SessionsScreen(sessions)
                    2->SlotsScreen(latest)
                    3->DirectorScreen(latest)
                    else->SettingsScreen(serviceReady)
                }
            }
        }
    }

    @Composable
    private fun TodayScreen(serviceReady:Boolean,recording:Boolean,latest:CaptureSession?,openOsm:()->Unit){
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            item{
                Text("Seu dia, organizado.",fontSize=30.sp,fontWeight=FontWeight.Bold,color=Color(0xFF17242B))
                Text("O app acompanha tudo o que você visita no OSM e preserva cada tela útil.",color=Color(0xFF68777E))
            }
            item{
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(20.dp)){
                    Column(Modifier.padding(18.dp)){
                        Text("Leitura automática",fontWeight=FontWeight.Bold,fontSize=18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if(recording)"Gravando sua navegação no OSM"
                            else if(serviceReady)"Pronta. Você só precisa abrir o OSM."
                            else "Ative a acessibilidade uma única vez."
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick=openOsm,enabled=serviceReady&&!recording,modifier=Modifier.fillMaxWidth()){
                            Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Abrir OSM")
                        }
                    }
                }
            }
            item{SessionSummaryCard(latest)}
            item{
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Metric("Telas",latest?.frames?.size?.toString()?:"0",Modifier.weight(1f))
                    Metric("Tipos",latest?.frames?.map{it.screenType}?.distinct()?.size?.toString()?:"0",Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun SessionSummaryCard(session:CaptureSession?){
        Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEFF5E5)),shape=RoundedCornerShape(20.dp)){
            Column(Modifier.padding(18.dp)){
                Text("Última sessão",fontWeight=FontWeight.Bold,fontSize=18.sp)
                if(session==null){
                    Text("Nenhuma sessão capturada ainda.")
                }else{
                    Text("${session.frames.size} telas preservadas")
                    Spacer(Modifier.height(8.dp))
                    val groups=session.frames.groupingBy{it.screenType}.eachCount()
                    Text(groups.entries.sortedByDescending{it.value}.joinToString(" • "){"${labelType(it.key)} ${it.value}"},
                        fontSize=13.sp,color=Color(0xFF55645B))
                }
            }
        }
    }

    @Composable
    private fun SessionsScreen(sessions:List<CaptureSession>){
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{
                Text("Jornada da sessão",fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("Tudo o que você navegou no OSM fica registrado aqui.",color=Color.Gray)
            }
            sessions.forEach{session->
                item{
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                        Column(Modifier.padding(16.dp)){
                            Text(formatTime(session.startedAt),fontWeight=FontWeight.Bold)
                            Text("${session.frames.size} telas • ${session.state}",fontSize=13.sp,color=Color.Gray)
                            Spacer(Modifier.height(10.dp))
                            session.frames.takeLast(14).forEach{frame->
                                Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                                    Surface(shape=RoundedCornerShape(8.dp),color=typeColor(frame.screenType)){
                                        Text(labelType(frame.screenType),Modifier.padding(horizontal=8.dp,vertical=4.dp),fontSize=11.sp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(frame.screenTitle,Modifier.weight(1f),maxLines=1)
                                    Text(SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(frame.capturedAt)),fontSize=11.sp,color=Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotsScreen(latest:CaptureSession?){
        val types=latest?.frames?.groupingBy{it.screenType}?.eachCount().orEmpty()
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            item{Text("Slots",fontSize=28.sp,fontWeight=FontWeight.Bold)}
            items((1..4).toList()){slot->
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(16.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Surface(shape=RoundedCornerShape(10.dp),color=Color(0xFF101E27)){
                                Text("S$slot",Modifier.padding(10.dp),color=Color(0xFFB8E34D),fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column{
                                Text("Slot $slot",fontWeight=FontWeight.Bold,fontSize=18.sp)
                                Text("Dados preenchidos conforme a navegação",fontSize=12.sp,color=Color.Gray)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Última sessão: ${types.values.sum()} telas disponíveis para processamento.",fontSize=13.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun DirectorScreen(latest:CaptureSession?){
        val markets=latest?.frames?.count{it.screenType=="market"}?:0
        val trainings=latest?.frames?.count{it.screenType=="training"}?:0
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            item{
                Text("Diretor",fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("Mercado, treino e evolução a partir das telas realmente visitadas.",color=Color.Gray)
            }
            item{
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Metric("Mercado",markets.toString(),Modifier.weight(1f))
                    Metric("Treinos",trainings.toString(),Modifier.weight(1f))
                }
            }
            item{
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(18.dp)){
                        Text("Nada é descartado",fontWeight=FontWeight.Bold)
                        Text("Se você abrir transferências, estádio, treino, classificação, resultado ou qualquer outra tela, ela fica preservada e categorizada na Jornada.")
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen(serviceReady:Boolean){
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            item{Text("Configurações",fontSize=28.sp,fontWeight=FontWeight.Bold)}
            item{
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(18.dp)){
                        Text("Leitura automática",fontWeight=FontWeight.Bold)
                        Text(if(serviceReady)"Ativa. Não precisa autorizar de novo ao abrir o app." else "Desativada no Android.")
                        if(!serviceReady){
                            Spacer(Modifier.height(10.dp))
                            Button(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("Abrir Acessibilidade")}
                        }
                    }
                }
            }
            item{
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(18.dp)){
                        Text("Arquitetura",fontWeight=FontWeight.Bold)
                        Text("Interface e armazenamento nativos. A internet é usada apenas quando o Coach precisa consultar IA/backend.")
                    }
                }
            }
        }
    }

    @Composable private fun Metric(label:String,value:String,modifier:Modifier=Modifier){
        Card(modifier,colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
            Column(Modifier.padding(16.dp)){Text(value,fontSize=28.sp,fontWeight=FontWeight.Bold);Text(label,color=Color.Gray)}
        }
    }

    private fun openOsm(){
        val launch=packageManager.getLaunchIntentForPackage(OSM_PACKAGE)
        if(launch!=null)startActivity(launch)
    }

    private fun isAccessibilityServiceEnabled():Boolean{
        val expected=ComponentName(this,OsmCaptureAccessibilityService::class.java).flattenToString()
        val enabled=runCatching{Settings.Secure.getInt(contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,0)==1}.getOrDefault(false)
        if(!enabled)return false
        val services=Settings.Secure.getString(contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?:return false
        return services.split(':').any{it.equals(expected,true)}
    }

    private fun formatTime(ms:Long)=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(Date(ms))
    private fun labelType(type:String)=when(type){
        "calendar"->"Calendário";"squad"->"Elenco";"match"->"Análise";"market"->"Mercado"
        "training"->"Treino";"club"->"Clube";"ranking"->"Tabela";"result"->"Resultado"
        "tactics"->"Tática";else->"Outra"
    }
    private fun typeColor(type:String)=when(type){
        "market"->Color(0xFFFFE7C2);"training"->Color(0xFFE1F3FF);"calendar"->Color(0xFFE8F4DD)
        "squad"->Color(0xFFEDE7FF);"match"->Color(0xFFFFE1E1);else->Color(0xFFECEFEF)
    }
}

@Composable
fun CoachTheme(content:@Composable()->Unit){
    MaterialTheme(
        colorScheme=lightColorScheme(
            primary=Color(0xFF182832),
            secondary=Color(0xFF7AA526),
            background=Color(0xFFF6F7F2),
            surface=Color.White
        ),
        content=content
    )
}
