package se.automatiziraj.termodom;

import android.app.Activity;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Native Xiaomi login, challenge, camera selection and inline LibVLC video. */
public final class CameraPanel extends LinearLayout implements AutoCloseable {
    private static final int INK=0xffedf4f6,MUTED=0xff9eafbd,TEAL=0xff74d1c0,AMBER=0xffe9b86e,BG=0xff0d1520,CARD=0xff182432;
    private static final String[] REGIONS={"Europa","Kina","Singapur","SAD","Rusija","Indija"},REGION_IDS={"de","cn","sg","us","ru","i2"};
    private final Activity activity;
    private final NativeCameraEngine engine;
    private final ExecutorService work=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private String region="de",account="";
    private volatile boolean closed,suspended;
    private boolean busy;
    private volatile int generation;
    private JSONArray devices=new JSONArray();
    private LibVLC vlc;
    private MediaPlayer player;
    private TextView videoStatus;
    private SurfaceView videoSurface;
    private FrameLayout videoFrame;
    private Button muteButton,snapshotButton,recordButton,zoomButton;
    private Dialog fullscreen;
    private ViewGroup videoParent;
    private ViewGroup.LayoutParams videoLayout;
    private int videoIndex;
    private volatile boolean videoReady,controlPending;
    private boolean muted=true,recording,recordPending,preview;
    private String quality="auto",activeSource,activeName;
    private float viewZoom=1;
    private File pendingExport,recordingDirectory;
    private LinearLayout deviceSettings;
    private JSONArray currentControls=new JSONArray();
    private volatile boolean ptzAvailable,ptzBusy;
    private Spinner qualitySelector;
    private LinearLayout ptzPad;
    private TextView preparedHint;
    private static final int EXPORT_CAPTURE=1710;
    public CameraPanel(Activity context){super(context);activity=context;engine=new NativeCameraEngine(context);setOrientation(VERTICAL);setTag("camera-native-card");setPadding(dp(17),dp(18),dp(17),dp(18));GradientDrawable background=new GradientDrawable();background.setColor(CARD);background.setCornerRadius(dp(23));background.setStroke(dp(1),0xff2a424a);setBackground(background);start();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView view=new TextView(activity);view.setText(value);view.setTextColor(color);view.setTextSize(size);view.setLineSpacing(dp(3),1);if(size>=17)view.setTypeface(Typeface.create("sans-serif-medium",0));return view;}
    private void add(View view){addView(view,new LinearLayout.LayoutParams(-1,-2));}
    private void gap(int height){addView(new View(activity),new LinearLayout.LayoutParams(1,dp(height)));}
    private Button button(String label,String tag,Runnable action){Button button=new Button(activity);button.setAllCaps(false);button.setText(label);button.setTextColor(INK);button.setTextSize(13);button.setMinHeight(dp(48));GradientDrawable bg=new GradientDrawable();bg.setColor(0xff223b40);bg.setCornerRadius(dp(14));bg.setStroke(dp(1),0xff2a424a);button.setBackground(bg);button.setPadding(dp(12),dp(10),dp(12),dp(10));button.setTag(tag);button.setOnClickListener(v->{if(!busy&&!closed)action.run();});return button;}
    private EditText input(String hint,int kind,String tag){EditText input=new EditText(activity);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setTextSize(15);input.setHint(hint);input.setSingleLine(true);input.setInputType(kind);input.setTag(tag);input.setContentDescription(hint);input.setMinHeight(dp(52));input.setBackgroundTintList(ColorStateList.valueOf(TEAL));return input;}
    private void base(String heading,String subtitle){generation++;controlPending=false;ptzBusy=false;stopVideo();deviceSettings=null;ptzPad=null;preparedHint=null;ptzAvailable=false;removeAllViews();add(text(heading,20,INK));gap(10);add(text(subtitle,13,MUTED));gap(19);}
    private void message(String value){TextView copy=text(value,12,AMBER);copy.setPadding(0,dp(10),0,dp(10));add(copy);}
    private void loading(String value){base("Vaša Xiaomi kamera",value);ProgressBar progress=new ProgressBar(activity);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(32),dp(32));lp.gravity=Gravity.CENTER;addView(progress,lp);gap(16);}
    private <T> void task(Callable<T> operation,Consumer<T> success){if(closed||busy)return;busy=true;final int requestGeneration=generation;setEnabledTree(this,false);work.execute(()->{try{T value=operation.call();main.post(()->{if(closed||suspended||requestGeneration!=generation)return;busy=false;setEnabledTree(this,true);success.accept(value);});}catch(Exception error){main.post(()->{if(closed||suspended||requestGeneration!=generation)return;busy=false;setEnabledTree(this,true);if(error instanceof NativeCameraEngine.Challenge)challenge(((NativeCameraEngine.Challenge)error).data);else failure(error.getMessage());});}});}
    private static void setEnabledTree(View view,boolean enabled){view.setEnabled(enabled);if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)setEnabledTree(group.getChildAt(i),enabled);}}
    private void start(){loading("Pokrećem ugrađeni modul kamere…");task(engine::accounts,accounts->{if(accounts.length()>0){account=accounts.optString(accounts.length()-1);account(accounts);}else login();});}
    private void regionSelector(){add(text("Regija Xiaomi Home računa",11,MUTED));Spinner selector=new Spinner(activity);ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,REGIONS);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);selector.setAdapter(adapter);selector.setMinimumHeight(dp(48));for(int i=0;i<REGION_IDS.length;i++)if(REGION_IDS[i].equals(region))selector.setSelection(i);selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){region=REGION_IDS[position];}});add(selector);gap(12);}
    private void login(){base("Prijava na Xiaomi račun","Prijavite se izvornim Xiaomi računom koji koristi vaša kamera. Prijava i video ostaju unutar TermoDoma.");EditText username=input("Xiaomi račun · e-pošta ili telefon",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,"camera-username");add(username);gap(10);EditText password=input("Lozinka Xiaomi računa",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,"camera-password");add(password);gap(14);regionSelector();add(button("Prijavi se","camera-login",()->{String user=username.getText().toString().trim(),pass=password.getText().toString();if(user.isEmpty()||pass.isEmpty()){message("Unesite korisničko ime i lozinku Xiaomi računa.");return;}password.setText("");task(()->{engine.login(user,pass);return engine.accounts();},accounts->{if(accounts.length()==0){failure("Xiaomi nije vratio prijavljeni račun.");return;}account=accounts.optString(accounts.length()-1);loadDevices();});}));gap(15);add(text("Lozinka se ne sprema. Sesija prijave šifrira se ključem ovog uređaja. Xiaomi može zatražiti sliku za provjeru ili kod poslan na telefon/e-poštu.",11,MUTED));gap(15);add(button("Obriši spremljenu prijavu","camera-forget",this::forget));}
    private void account(JSONArray accounts){base("Xiaomi račun je spremljen","Odaberite regiju u kojoj je kamera dodana na Xiaomi Home račun.");if(accounts.length()>1){String[] names=new String[accounts.length()];for(int i=0;i<names.length;i++)names[i]=accounts.optString(i);Spinner users=new Spinner(activity);users.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,names));users.setMinimumHeight(dp(48));users.setSelection(names.length-1);users.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){account=names[position];}});add(users);gap(12);}regionSelector();add(button("Učitaj moje kamere","camera-load-devices",this::loadDevices));gap(12);add(button("Prijavi drugi račun","camera-other-account",this::login));gap(12);add(button("Odjavi ovaj uređaj","camera-forget",this::forget));}
    private void challenge(JSONObject data){base("Potvrda Xiaomi prijave","Dovršite provjeru koju traži izvorni Xiaomi servis.");String captcha=data.optString("captcha","");String kind;
        if(!captcha.isEmpty()){kind="captcha";try{byte[] bytes=Base64.decode(captcha,Base64.DEFAULT);if(bytes.length>1_000_000)throw new Exception();BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<1||options.outHeight<1||options.outWidth>3000||options.outHeight>3000)throw new Exception();Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);ImageView view=new ImageView(activity);view.setImageBitmap(image);view.setAdjustViewBounds(true);view.setContentDescription("Slika Xiaomi provjere");add(view);gap(14);}catch(Exception error){message("Slika provjere nije dostupna. Ponovno pokrenite prijavu.");}}
        else{kind="verify";String phone=data.optString("verify_phone",""),email=data.optString("verify_email","");add(text("Xiaomi je poslao kod na "+(!phone.isEmpty()?phone:!email.isEmpty()?email:"vaš način potvrde")+".",14,INK));gap(12);}
        EditText answer=input(kind.equals("captcha")?"Znakovi sa slike":"Kod potvrde",kind.equals("captcha")?InputType.TYPE_CLASS_TEXT:InputType.TYPE_CLASS_NUMBER,"camera-challenge");add(answer);gap(16);add(button("Potvrdi","camera-confirm",()->{String value=answer.getText().toString().trim();if(value.isEmpty())return;answer.setText("");task(()->{engine.challenge(kind,value);return engine.accounts();},accounts->{if(accounts.length()==0){failure("Xiaomi nije dovršio prijavu.");return;}account=accounts.optString(accounts.length()-1);loadDevices();});}));gap(12);add(button("Nova prijava","camera-restart-login",this::login));}
    private void loadDevices(){loading("Dohvaćam kamere s vašeg Xiaomi računa…");task(()->engine.cameras(account,region),this::devices);}
    private void devices(JSONArray found){devices=found;base("Moje kamere","Odaberite kameru za prikaz uživo u ovoj aplikaciji.");if(found.length()==0)message("Nema kamera u odabranoj regiji ili Xiaomi nije odobrio njihov prikaz.");for(int i=0;i<found.length();i++){JSONObject camera=found.optJSONObject(i);if(camera==null)continue;String source=camera.optString("url"),name=camera.optString("name","Xiaomi kamera");add(text(name,17,INK));String model=Uri.parse(source).getQueryParameter("model");if(model!=null){gap(5);add(text(model,11,MUTED));}gap(10);add(button("Prikaži uživo","camera-play-"+i,()->play(source,name)));gap(10);add(button("Postavke kamere","camera-settings-"+i,()->settings(source,name)));gap(20);}add(text("Dostupnost prijenosa ovisi o modelu kamere i Xiaomi računu. Sam prikaz računa ne potvrđuje podršku za video.",11,MUTED));gap(15);add(button("Osvježi kamere","camera-refresh",this::loadDevices));gap(12);add(button("Promijeni regiju ili račun","camera-account",()->task(engine::accounts,this::account)));gap(12);add(button("Odjavi ovaj uređaj","camera-forget",this::forget));}
    private void settings(String source,String name){
        preview=false;activeSource=source;activeName=name;loading("Učitavam potvrđene postavke modela…");
        task(()->engine.controlsForSource(withQuality(source,quality)),data->{
            base(name,"Postavke koje je potvrdio model vaše kamere");preparedControls();ptzAvailable=data.optBoolean("ptz");currentControls=data.optJSONArray("controls");if(currentControls==null)currentControls=new JSONArray();renderControls();gap(18);add(button("Prikaži uživo","camera-settings-play",()->play(source,name)));gap(10);add(button("Moje kamere","camera-settings-back",()->devices(devices)));
        });
    }
    private void play(String source,String name){
        if(controlPending||ptzBusy)return;
        activeSource=source;activeName=name;preview=false;
        loading("Povezujem prijenos kamere…");
        task(()->engine.play(withQuality(source,quality)),uri->video(uri,name));
    }
    private static String withQuality(String source,String quality){
        Uri original=Uri.parse(source);Uri.Builder builder=original.buildUpon().clearQuery();
        for(String key:original.getQueryParameterNames())if(!key.equals("subtype"))for(String value:original.getQueryParameters(key))builder.appendQueryParameter(key,value);
        return builder.appendQueryParameter("subtype",quality).build().toString();
    }
    private void controlRow(View first,View second){
        LinearLayout row=new LinearLayout(activity);row.setOrientation(HORIZONTAL);
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(52),1);left.rightMargin=dp(5);row.addView(first,left);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(52),1);right.leftMargin=dp(5);row.addView(second,right);add(row);gap(10);
    }
    private void video(String uri,String name){
        base(name,"Prikaz kamere unutar TermoDoma");
        videoFrame=new FrameLayout(activity);videoFrame.setBackgroundColor(0xff000000);videoFrame.setTag("camera-video-frame");
        int width=getResources().getConfiguration().screenWidthDp-70;
        addView(videoFrame,new LinearLayout.LayoutParams(-1,dp(Math.max(175,width*9f/16))));
        videoSurface=new SurfaceView(activity);videoSurface.setTag("camera-video-surface");videoFrame.addView(videoSurface,new FrameLayout.LayoutParams(-1,-1));
        if(preview){TextView label=text("Pregled sučelja · bez prijenosa",13,MUTED);label.setGravity(Gravity.CENTER);label.setTag("camera-preview-label");videoFrame.addView(label,new FrameLayout.LayoutParams(-1,-1));}
        gap(10);videoStatus=text(preview?"Pregled sučelja · bez prijenosa":"Pripremam sliku…",12,AMBER);videoStatus.setTag("camera-video-status");add(videoStatus);gap(14);
        muteButton=button("Zvuk · isključen","camera-mute",this::toggleMute);
        Button full=button("Cijeli zaslon","camera-fullscreen",this::showFullscreen);controlRow(muteButton,full);
        snapshotButton=button("Spremi fotografiju","camera-snapshot",this::snapshot);
        recordButton=button("Snimi video","camera-record",this::toggleRecording);controlRow(snapshotButton,recordButton);
        zoomButton=button("Uvećanje prikaza · 1×","camera-zoom",()->{viewZoom=viewZoom==1?2:1;if(videoSurface!=null){videoSurface.setScaleX(viewZoom);videoSurface.setScaleY(viewZoom);}zoomButton.setText("Uvećanje prikaza · "+(viewZoom==1?"1×":"2×"));});add(zoomButton);gap(10);
        add(text("Kvaliteta prijenosa",11,MUTED));
        Spinner profiles=new Spinner(activity);qualitySelector=profiles;profiles.setTag("camera-quality");profiles.setMinimumHeight(dp(48));
        String[] values={"Automatski","SD","HD"},ids={"auto","sd","hd"};
        profiles.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,values));
        for(int i=0;i<ids.length;i++)if(ids[i].equals(quality))profiles.setSelection(i);
        profiles.setEnabled(!preview);
        profiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(AdapterView<?> parent){}
            public void onItemSelected(AdapterView<?> parent,View selected,int position,long id){String next=ids[position];if(!next.equals(quality)&&!preview&&!controlPending&&!ptzBusy){quality=next;if(activeSource!=null)play(activeSource,activeName);}}
        });add(profiles);gap(5);add(text("Dostupnu kvalitetu i rezoluciju određuje kamera. Snimanje se zaustavlja kad zatvorite prikaz.",11,MUTED));gap(17);
        add(button(preview?"Natrag na prijavu":"Moje kamere","camera-stop",()->{preview=false;if(devices.length()>0)devices(devices);else start();}));gap(17);
        preparedControls();updateControls();
        if(preview)return;
        try{
            vlc=new LibVLC(activity,new ArrayList<>(Arrays.asList("--rtsp-tcp","--network-caching=500","--no-metadata-network-access","--no-lua","--no-video-title-show","--quiet","--verbose=-1")));
            player=new MediaPlayer(vlc);attachVideo();player.setVolume(muted?0:100);
            videoSurface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(player!=null&&player.getVLCVout().areViewsAttached())player.getVLCVout().setWindowSize(r-l,b-t);});
            player.setEventListener(event->{
                if(closed||suspended||videoStatus==null)return;
                if(event.type==MediaPlayer.Event.Vout&&event.getVoutCount()>0&&player!=null&&player.getVLCVout().areViewsAttached()){
                    videoReady=true;videoStatus.setText("Prijenos uživo");videoStatus.setTextColor(TEAL);updateControls();
                }else if(event.type==MediaPlayer.Event.RecordChanged){
                    boolean wasRecording=recording;recording=event.getRecording();recordPending=false;updateControls();
                    if(recording)videoStatus.setText("Prijenos uživo · snimanje na uređaj");
                    else if(wasRecording){videoStatus.setText("Prijenos uživo");String path=event.getRecordPath();if(path!=null)exportRecording(path);else notifyUser("Video nije spremljen. Kamera nije vratila snimku.");}
                }else if(event.type==MediaPlayer.Event.EncounteredError){
                    videoReady=false;videoStatus.setText("Kamera nije vratila sliku. Provjerite model i mrežu pa pokušajte ponovno.");videoStatus.setTextColor(AMBER);updateControls();
                }
            });
            Media media=new Media(vlc,Uri.parse(uri));media.setHWDecoderEnabled(true,false);media.addOption(":rtsp-tcp");player.setMedia(media);media.release();player.play();loadControls();
        }catch(Exception error){videoReady=false;if(videoStatus!=null)videoStatus.setText("Izvorni video modul nije dostupan. Ponovno instalirajte aplikaciju.");updateControls();}
    }
    private void preparedControls(){
        TextView heading=text("Postavke kamere",16,INK);heading.setTag("camera-prepared-settings");add(heading);gap(8);
        deviceSettings=new LinearLayout(activity);deviceSettings.setOrientation(VERTICAL);deviceSettings.setTag("camera-device-controls");add(deviceSettings);
        if(preview)deviceSettings.addView(text("Pregled sučelja · mogućnosti stvarne kamere učitavaju se nakon prijave.",11,MUTED));
        else deviceSettings.addView(text("Provjeravam mogućnosti modela…",11,MUTED));gap(12);
        add(text("U pripremi · dodatne mogućnosti",11,AMBER));gap(9);
        preparedHint=text("",12,MUTED);add(preparedHint);updatePreparedHint();
    }
    private void loadControls(){
        final int requestGeneration=generation;final LinearLayout target=deviceSettings;
        work.execute(()->{try{JSONObject data=engine.controls();main.post(()->{if(closed||suspended||requestGeneration!=generation||target!=deviceSettings)return;ptzAvailable=data.optBoolean("ptz");currentControls=data.optJSONArray("controls");if(currentControls==null)currentControls=new JSONArray();renderControls();});}catch(Exception failure){main.post(()->{if(closed||suspended||requestGeneration!=generation||target!=deviceSettings)return;deviceSettings.removeAllViews();deviceSettings.addView(text("Postavke ovog modela trenutačno nisu dostupne. Kontrole ostaju u pripremi.",12,MUTED));});}});
    }
    private boolean sameValue(Object first,Object second){return first!=null&&second!=null&&String.valueOf(first).equals(String.valueOf(second));}
    private void renderControls(){
        if(deviceSettings==null)return;deviceSettings.removeAllViews();
        if(currentControls.length()==0)deviceSettings.addView(text("Xiaomi za ovaj model nije potvrdio dodatne postavke.",12,MUTED));
        if(ptzAvailable){
            deviceSettings.addView(text("Pomicanje kamere",14,INK));ptzPad=new LinearLayout(activity);ptzPad.setOrientation(VERTICAL);ptzPad.setTag("camera-ptz-pad");
            LinearLayout top=new LinearLayout(activity);Button up=button("↑ Gore","camera-ptz-up",()->sendPtz("up"));top.addView(up,new LinearLayout.LayoutParams(-1,dp(48)));ptzPad.addView(top);
            LinearLayout middle=new LinearLayout(activity);Button left=button("← Lijevo","camera-ptz-left",()->sendPtz("left")),right=button("Desno →","camera-ptz-right",()->sendPtz("right"));LinearLayout.LayoutParams half=new LinearLayout.LayoutParams(0,dp(48),1);middle.addView(left,half);middle.addView(right,new LinearLayout.LayoutParams(0,dp(48),1));ptzPad.addView(middle);
            Button down=button("↓ Dolje","camera-ptz-down",()->sendPtz("down"));ptzPad.addView(down,new LinearLayout.LayoutParams(-1,dp(48)));deviceSettings.addView(ptzPad);deviceSettings.addView(text("Jedan dodir šalje jedan ograničen pomak. Potreban je aktivan prijenos.",11,MUTED));setEnabledTree(ptzPad,videoReady&&!preview&&!ptzBusy&&!controlPending);
        }else ptzPad=null;
        updatePreparedHint();
        for(int i=0;i<currentControls.length();i++){
            JSONObject control=currentControls.optJSONObject(i);if(control==null)continue;
            String id=control.optString("id"),label=control.optString("label",id),format=control.optString("format");
            boolean known=control.optBoolean("known"),writable=control.optBoolean("writable");Object value=control.opt("value");
            LinearLayout row=new LinearLayout(activity);row.setOrientation(VERTICAL);row.setPadding(0,dp(7),0,dp(9));deviceSettings.addView(row,new LinearLayout.LayoutParams(-1,-2));
            TextView caption=text(label,13,INK);row.addView(caption);
            if(!known){row.addView(text("U pripremi · trenutačno stanje nije potvrđeno",11,MUTED));continue;}
            JSONArray options=control.optJSONArray("options");
            if(format.equals("bool")&&value instanceof Boolean){
                Switch toggle=new Switch(activity);toggle.setText((Boolean)value?"Uključeno":"Isključeno");toggle.setTextColor(MUTED);toggle.setTextSize(12);toggle.setMinHeight(dp(48));toggle.setChecked((Boolean)value);toggle.setEnabled(writable);toggle.setTag("camera-control-"+id);toggle.setThumbTintList(ColorStateList.valueOf(TEAL));
                toggle.setOnCheckedChangeListener((button,checked)->{button.setOnCheckedChangeListener(null);button.setChecked((Boolean)value);writeControl(control,checked);});row.addView(toggle);
            }else if(format.equals("time")&&value instanceof String&&((String)value).matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")){
                String time=(String)value;Button choose=button(time,"camera-control-"+id,()->{String[] parts=time.split(":");new TimePickerDialog(activity,(picker,hour,minute)->writeControl(control,String.format(Locale.ROOT,"%02d:%02d",hour,minute)),Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),true).show();});choose.setEnabled(writable);row.addView(choose,new LinearLayout.LayoutParams(-1,dp(48)));
            }else if(options!=null&&options.length()>0){
                String[] labels=new String[options.length()];Object[] values=new Object[options.length()];int selected=-1;
                for(int o=0;o<labels.length;o++){JSONObject option=options.optJSONObject(o);labels[o]=option==null?"":option.optString("label");values[o]=option==null?null:option.opt("value");if(sameValue(values[o],value))selected=o;}
                if(selected<0){row.addView(text("Vrijednost: "+String.valueOf(value)+" · čitanje bez dostupne opcije",11,MUTED));continue;}
                Spinner selector=new Spinner(activity);selector.setTag("camera-control-"+id);selector.setMinimumHeight(dp(48));selector.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));selector.setSelection(selected);selector.setEnabled(writable);
                final int confirmedSelection=selected;selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View view,int position,long item){if(!sameValue(values[position],control.opt("value"))){selector.setOnItemSelectedListener(null);selector.setSelection(confirmedSelection);writeControl(control,values[position]);}}});row.addView(selector);
            }else if(writable&&control.has("minimum")&&control.has("maximum")&&value instanceof Number){
                double min=control.optDouble("minimum"),max=control.optDouble("maximum"),step=control.optDouble("step",1),current=((Number)value).doubleValue();
                double count=(max-min)/step;if(!Double.isFinite(min)||!Double.isFinite(max)||!Double.isFinite(step)||step<=0||count<=0||count>1000){row.addView(text("Vrijednost: "+String.valueOf(value),12,MUTED));continue;}
                TextView amount=text(String.valueOf(value),12,TEAL);row.addView(amount);SeekBar range=new SeekBar(activity);range.setTag("camera-control-"+id);range.setMinimumHeight(dp(48));range.setMax((int)Math.round(count));range.setProgress((int)Math.round((current-min)/step));range.setProgressTintList(ColorStateList.valueOf(TEAL));range.setThumbTintList(ColorStateList.valueOf(TEAL));
                range.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar bar){}public void onProgressChanged(SeekBar bar,int progress,boolean user){if(user)amount.setText(String.valueOf(min+progress*step));}public void onStopTrackingTouch(SeekBar bar){double next=min+bar.getProgress()*step;Object numeric=format.startsWith("uint")||format.startsWith("int")?(Object)Long.valueOf(Math.round(next)):Double.valueOf(next);bar.setProgress((int)Math.round((current-min)/step));amount.setText(String.valueOf(value));writeControl(control,numeric);}});row.addView(range);
            }else row.addView(text("Stanje: "+String.valueOf(value)+(writable?"":" · samo čitanje"),12,MUTED));
        }
    }
    private void writeControl(JSONObject previous,Object value){
        if(closed||suspended||busy||preview||controlPending||ptzBusy||!previous.optBoolean("known")||!previous.optBoolean("writable"))return;
        controlPending=true;updateControls();
        final String id=previous.optString("id");final int requestGeneration=generation;final LinearLayout target=deviceSettings;setEnabledTree(target,false);target.addView(text("Čekam potvrdu kamere…",12,AMBER));
        work.execute(()->{try{JSONObject result=engine.setControl(id,value);JSONObject updated=result.optJSONObject("control");main.post(()->{if(closed||suspended||requestGeneration!=generation||target!=deviceSettings)return;controlPending=false;try{for(int i=0;i<currentControls.length();i++)if(id.equals(currentControls.getJSONObject(i).optString("id"))){currentControls.put(i,updated==null?new JSONObject(previous.toString()).put("known",false):updated);break;}}catch(Exception ignored){}renderControls();updateControls();if(result.optBoolean("confirmed")&&id.equals("camera-on")&&updated!=null&&updated.optBoolean("known")&&Boolean.FALSE.equals(updated.opt("value")))stopForPrivacy();if(!result.optBoolean("confirmed"))notifyUser("Promjena nije potvrđena čitanjem kamere. Provjerite stvarno stanje.");});}catch(Exception failure){main.post(()->{if(closed||suspended||requestGeneration!=generation||target!=deviceSettings)return;controlPending=false;try{previous.put("known",false);}catch(Exception ignored){}renderControls();updateControls();notifyUser("Xiaomi nije potvrdio promjenu postavke.");});}});
    }
    private void updatePreparedHint(){if(preparedHint!=null)preparedHint.setText((ptzAvailable?"":"Pomicanje / PTZ · ")+"razgovor preko mikrofona\nArhiva / SD kartica · cloud snimke · obavijesti\nOstale postavke privatnosti, noćnog prikaza i detekcije prikazuju se iznad samo kad ih potvrdi model kamere.");}
    private void stopForPrivacy(){
        TextView status=videoStatus;FrameLayout frame=videoFrame;
        if(muteButton!=null)muteButton.setEnabled(false);if(snapshotButton!=null)snapshotButton.setEnabled(false);if(recordButton!=null)recordButton.setEnabled(false);if(zoomButton!=null)zoomButton.setEnabled(false);if(ptzPad!=null)setEnabledTree(ptzPad,false);
        stopVideo();
        if(status!=null){status.setText("Kamera je isključena · prijenos je zaustavljen");status.setTextColor(MUTED);}
        if(frame!=null){frame.removeAllViews();TextView privateView=text("Kamera je isključena",14,MUTED);privateView.setGravity(Gravity.CENTER);frame.addView(privateView,new FrameLayout.LayoutParams(-1,-1));}
    }
    private void sendPtz(String direction){
        if(!ptzAvailable||!videoReady||preview||ptzBusy||controlPending)return;ptzBusy=true;updateControls();final int requestGeneration=generation;
        work.execute(()->{try{if(closed||suspended||requestGeneration!=generation||controlPending||!videoReady||!ptzAvailable)return;JSONObject response=engine.ptz(direction);main.post(()->{if(closed||suspended||requestGeneration!=generation)return;if(!response.optBoolean("sent"))notifyUser("Kamera nije prihvatila pomak.");else if(!response.optBoolean("acknowledged"))notifyUser("Pomak je zatražen. Kamera nije potvrdila izvršenje.");});}catch(Exception failure){main.post(()->{if(!closed&&!suspended&&requestGeneration==generation)notifyUser("Pomicanje trenutačno nije dostupno za ovaj prijenos.");});}finally{main.postDelayed(()->{if(!closed&&requestGeneration==generation){ptzBusy=false;updateControls();}},650);}});
    }
    private void updateControls(){
        boolean ready=videoReady&&!preview&&player!=null&&!controlPending;
        if(muteButton!=null){muteButton.setEnabled(ready);muteButton.setText(muted?"Zvuk · isključen":"Zvuk · uključen");}
        if(snapshotButton!=null)snapshotButton.setEnabled(ready&&!recordPending);
        if(zoomButton!=null)zoomButton.setEnabled(ready);
        if(recordButton!=null){recordButton.setEnabled(ready&&!recordPending);recordButton.setText(recordPending?"Pričekajte…":recording?"Zaustavi snimanje":"Snimi video");recordButton.setTextColor(recording?AMBER:INK);}
        if(ptzPad!=null)setEnabledTree(ptzPad,ready&&!ptzBusy);
        if(qualitySelector!=null)qualitySelector.setEnabled(!preview&&!controlPending&&!ptzBusy);
    }
    private void toggleMute(){if(player==null||!videoReady)return;boolean next=!muted;if(player.setVolume(next?0:100)!=-1){muted=next;updateControls();}else notifyUser("Zvuk trenutačno nije dostupan.");}
    private void attachVideo(){if(player!=null&&videoSurface!=null){player.getVLCVout().setVideoView(videoSurface);player.getVLCVout().attachViews();videoSurface.post(()->{if(player!=null&&videoSurface!=null&&player.getVLCVout().areViewsAttached())player.getVLCVout().setWindowSize(videoSurface.getWidth(),videoSurface.getHeight());});}}
    private void showFullscreen(){
        if(videoFrame==null||fullscreen!=null)return;
        videoParent=(ViewGroup)videoFrame.getParent();if(videoParent==null)return;videoIndex=videoParent.indexOfChild(videoFrame);videoLayout=videoFrame.getLayoutParams();
        if(player!=null&&player.getVLCVout().areViewsAttached())player.getVLCVout().detachViews();videoParent.removeView(videoFrame);
        Dialog dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);FrameLayout screen=new FrameLayout(activity);screen.setBackgroundColor(0xff000000);
        screen.addView(videoFrame,new FrameLayout.LayoutParams(-1,-1));Button back=button("Zatvori cijeli zaslon","camera-fullscreen-close",dialog::dismiss);FrameLayout.LayoutParams close=new FrameLayout.LayoutParams(-2,dp(48),Gravity.BOTTOM|Gravity.END);close.setMargins(dp(12),dp(12),dp(12),dp(20));screen.addView(back,close);
        dialog.setContentView(screen);dialog.setOnDismissListener(ignored->{
            if(player!=null&&player.getVLCVout().areViewsAttached())player.getVLCVout().detachViews();
            if(videoFrame!=null){ViewGroup parent=(ViewGroup)videoFrame.getParent();if(parent!=null)parent.removeView(videoFrame);if(videoParent!=null)videoParent.addView(videoFrame,Math.min(videoIndex,videoParent.getChildCount()),videoLayout);}
            fullscreen=null;videoParent=null;attachVideo();
        });fullscreen=dialog;dialog.show();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.black);window.setLayout(-1,-1);window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);}attachVideo();
    }
    private File captureDirectory() throws IOException {File directory=new File(activity.getCacheDir(),"camera-captures");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException();return directory;}
    private void snapshot(){
        if(!videoReady||videoSurface==null||player==null)return;int width=videoSurface.getWidth(),height=videoSurface.getHeight();
        if(width<1||height<1||width>4096||height>4096){notifyUser("Fotografija trenutačno nije dostupna.");return;}
        final int requestGeneration=generation;Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{PixelCopy.request(videoSurface,bitmap,result->{
            if(result!=PixelCopy.SUCCESS||closed||requestGeneration!=generation){bitmap.recycle();if(!closed)notifyUser("Fotografija nije snimljena. Pričekajte sliku kamere.");return;}
            work.execute(()->{File file=null;try{file=File.createTempFile("snapshot-",".png",captureDirectory());try(OutputStream output=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException();}final File ready=file;main.post(()->{if(closed||suspended||requestGeneration!=generation){ready.delete();return;}offerExport(ready,"image/png","TermoDom-fotografija.png");});}catch(Exception error){if(file!=null)file.delete();main.post(()->notifyUser("Fotografija nije spremljena."));}finally{bitmap.recycle();}});
        },main);}catch(Exception error){bitmap.recycle();notifyUser("Fotografija trenutačno nije dostupna.");}
    }
    private void toggleRecording(){
        if(player==null||!videoReady||recordPending)return;
        try{boolean stopping=recording;if(!stopping&&recordingDirectory==null)recordingDirectory=java.nio.file.Files.createTempDirectory(captureDirectory().toPath(),"recording-").toFile();if(!player.record(stopping?null:recordingDirectory.getAbsolutePath())){if(!stopping){cleanupRecordingDirectory(recordingDirectory);recordingDirectory=null;}notifyUser("Ovaj prijenos ne podržava snimanje na uređaj.");return;}recordPending=true;updateControls();int requestedGeneration=generation;main.postDelayed(()->{if(closed||requestedGeneration!=generation||!recordPending)return;recordPending=false;updateControls();notifyUser("Kamera još nije potvrdila promjenu snimanja.");},8000);}catch(Exception error){notifyUser("Snimanje trenutačno nije dostupno.");}
    }
    private void exportRecording(String path){
        try{File directory=captureDirectory().getCanonicalFile(),file=new File(path).getCanonicalFile();if(!file.isFile()||file.length()==0||!file.getPath().startsWith(directory.getPath()+File.separator)){notifyUser("Video nije dostupan za spremanje.");return;}String filename=file.getName().toLowerCase(Locale.ROOT);String extension=filename.contains(".")?filename.substring(filename.lastIndexOf('.')+1):"video";String type=extension.equals("mp4")?"video/mp4":extension.equals("mkv")?"video/x-matroska":extension.equals("ts")?"video/mp2t":"application/octet-stream";recordingDirectory=null;offerExport(file,type,"TermoDom-snimka."+extension);}catch(Exception error){notifyUser("Video nije dostupan za spremanje.");}
    }
    private void cleanupRecordingDirectory(File directory){
        if(directory==null)return;try{File root=captureDirectory().getCanonicalFile(),target=directory.getCanonicalFile();if(!root.equals(target.getParentFile())||!target.getName().startsWith("recording-"))return;File[] files=target.listFiles();if(files!=null)for(File file:files)if(file.isFile()&&(pendingExport==null||!file.getCanonicalFile().equals(pendingExport.getCanonicalFile())))file.delete();target.delete();}catch(Exception ignored){}
    }
    private void discardExport(File file){if(file==null)return;File directory=file.getParentFile();file.delete();cleanupRecordingDirectory(directory);}
    private void offerExport(File file,String type,String name){
        if(pendingExport!=null)discardExport(pendingExport);pendingExport=file;
        try{Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name);activity.startActivityForResult(save,EXPORT_CAPTURE);}catch(Exception error){File cancelled=pendingExport;pendingExport=null;discardExport(cancelled);notifyUser("Nije moguće odabrati datoteku za spremanje.");}
    }
    public boolean onActivityResult(int request,int result,Intent data){
        if(request!=EXPORT_CAPTURE)return false;File file=pendingExport;pendingExport=null;if(file==null)return true;
        Uri destination=data==null?null:data.getData();if(result!=Activity.RESULT_OK||destination==null||!"content".equals(destination.getScheme())){discardExport(file);return true;}
        work.execute(()->{boolean saved=false;try(InputStream input=new FileInputStream(file);OutputStream output=activity.getContentResolver().openOutputStream(destination,"wt")){if(output==null)throw new IOException();byte[] buffer=new byte[64*1024];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);saved=true;}catch(Exception ignored){}finally{discardExport(file);}final boolean success=saved;main.post(()->notifyUser(success?"Datoteka je spremljena.":"Datoteka nije spremljena."));});return true;
    }
    private void notifyUser(String message){if(!activity.isFinishing())Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
    /** Synthetic layout preview: never contacts a provider or claims that a camera is online. */
    public void showPreviewForTest(){
        if(!BuildConfig.DEBUG)throw new IllegalStateException();generation++;busy=false;engine.stop();preview=true;video(null,"Mi 360 · pregled sučelja");
        try{
            currentControls=new JSONArray()
                .put(new JSONObject().put("id","camera-on").put("label","Primjer · uključivanje kamere").put("format","bool").put("readable",true).put("writable",true).put("known",true).put("value",true))
                .put(new JSONObject().put("id","night-shot").put("label","Primjer · noćni prikaz").put("format","uint8").put("readable",true).put("writable",true).put("known",true).put("value",2).put("options",new JSONArray().put(new JSONObject().put("value",0).put("label","Uključeno")).put(new JSONObject().put("value",1).put("label","Isključeno")).put(new JSONObject().put("value",2).put("label","Automatski"))))
                .put(new JSONObject().put("id","indicator-light").put("label","Primjer · statusna lampica").put("format","bool").put("readable",true).put("writable",true).put("known",true).put("value",true))
                .put(new JSONObject().put("id","speaker-volume").put("label","Primjer · glasnoća zvučnika").put("format","uint8").put("readable",true).put("writable",true).put("known",true).put("value",50).put("minimum",0).put("maximum",100).put("step",1));
            ptzAvailable=true;renderControls();setEnabledTree(deviceSettings,false);
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    private void stopVideo(){
        videoReady=false;if(fullscreen!=null)fullscreen.dismiss();videoStatus=null;
        if(player!=null){player.setEventListener(null);if(recording||recordPending)player.record(null);player.stop();if(player.getVLCVout().areViewsAttached())player.getVLCVout().detachViews();player.release();player=null;}
        if(vlc!=null){vlc.release();vlc=null;}cleanupRecordingDirectory(recordingDirectory);recordingDirectory=null;recording=false;recordPending=false;viewZoom=1;videoSurface=null;videoFrame=null;muteButton=null;snapshotButton=null;recordButton=null;zoomButton=null;qualitySelector=null;
    }

    private void failure(String message){base("Kamera trenutačno nije dostupna",message==null?"Provjerite prijavu i mrežnu vezu.":message);add(button("Pokušaj ponovno","camera-retry",this::start));gap(12);add(button("Nova prijava","camera-login-again",this::login));gap(12);add(button("Obriši spremljenu prijavu","camera-forget",this::forget));}
    private void forget(){engine.forget();devices=new JSONArray();account="";login();}
    public void onHostPause(){generation++;controlPending=false;ptzBusy=false;suspended=true;stopVideo();engine.stop();}
    public void onHostResume(){if(suspended&&!closed){suspended=false;busy=false;start();}}
    @Override public void close(){generation++;controlPending=false;ptzBusy=false;closed=true;main.removeCallbacksAndMessages(null);stopVideo();engine.close();work.shutdownNow();}
}
