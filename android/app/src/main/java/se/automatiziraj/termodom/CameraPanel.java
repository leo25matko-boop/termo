package se.automatiziraj.termodom;

import android.app.Activity;
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
import android.view.SurfaceView;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import java.util.*;
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
    private boolean closed,suspended,busy;
    private int generation;
    private JSONArray devices=new JSONArray();
    private LibVLC vlc;
    private MediaPlayer player;
    private TextView videoStatus;
    public CameraPanel(Activity context){super(context);activity=context;engine=new NativeCameraEngine(context);setOrientation(VERTICAL);setTag("camera-native-card");setPadding(dp(17),dp(18),dp(17),dp(18));GradientDrawable background=new GradientDrawable();background.setColor(CARD);background.setCornerRadius(dp(23));background.setStroke(dp(1),0xff2a424a);setBackground(background);start();}
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView view=new TextView(activity);view.setText(value);view.setTextColor(color);view.setTextSize(size);view.setLineSpacing(dp(3),1);if(size>=17)view.setTypeface(Typeface.create("sans-serif-medium",0));return view;}
    private void add(View view){addView(view,new LinearLayout.LayoutParams(-1,-2));}
    private void gap(int height){addView(new View(activity),new LinearLayout.LayoutParams(1,dp(height)));}
    private Button button(String label,String tag,Runnable action){Button button=new Button(activity);button.setAllCaps(false);button.setText(label);button.setTextColor(INK);button.setTextSize(13);button.setMinHeight(dp(48));GradientDrawable bg=new GradientDrawable();bg.setColor(0xff223b40);bg.setCornerRadius(dp(14));bg.setStroke(dp(1),0xff2a424a);button.setBackground(bg);button.setPadding(dp(12),dp(10),dp(12),dp(10));button.setTag(tag);button.setOnClickListener(v->{if(!busy&&!closed)action.run();});return button;}
    private EditText input(String hint,int kind,String tag){EditText input=new EditText(activity);input.setTextColor(INK);input.setHintTextColor(MUTED);input.setTextSize(15);input.setHint(hint);input.setSingleLine(true);input.setInputType(kind);input.setTag(tag);input.setContentDescription(hint);input.setMinHeight(dp(52));input.setBackgroundTintList(ColorStateList.valueOf(TEAL));return input;}
    private void base(String heading,String subtitle){stopVideo();removeAllViews();add(text(heading,20,INK));gap(10);add(text(subtitle,13,MUTED));gap(19);}
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
    private void devices(JSONArray found){devices=found;base("Moje kamere","Odaberite kameru za prikaz uživo u ovoj aplikaciji.");if(found.length()==0)message("Nema kamera u odabranoj regiji ili Xiaomi nije odobrio njihov prikaz.");for(int i=0;i<found.length();i++){JSONObject camera=found.optJSONObject(i);if(camera==null)continue;String source=camera.optString("url"),name=camera.optString("name","Xiaomi kamera");add(text(name,17,INK));String model=Uri.parse(source).getQueryParameter("model");if(model!=null){gap(5);add(text(model,11,MUTED));}gap(10);add(button("Prikaži uživo","camera-play-"+i,()->play(source,name)));gap(20);}add(text("Dostupnost prijenosa ovisi o modelu kamere i Xiaomi računu. Sam prikaz računa ne potvrđuje podršku za video.",11,MUTED));gap(15);add(button("Osvježi kamere","camera-refresh",this::loadDevices));gap(12);add(button("Promijeni regiju ili račun","camera-account",()->task(engine::accounts,this::account)));gap(12);add(button("Odjavi ovaj uređaj","camera-forget",this::forget));}
    private void play(String source,String name){loading("Povezujem prijenos kamere…");task(()->engine.play(source),uri->video(uri,name));}
    private void video(String uri,String name){base(name,"Prikaz kamere unutar TermoDoma");FrameLayout frame=new FrameLayout(activity);frame.setBackgroundColor(0xff000000);int width=getResources().getConfiguration().screenWidthDp-70;addView(frame,new LinearLayout.LayoutParams(-1,dp(Math.max(175,width*9f/16))));SurfaceView surface=new SurfaceView(activity);surface.setTag("camera-video-surface");frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));gap(12);videoStatus=text("Pripremam sliku…",12,AMBER);videoStatus.setTag("camera-video-status");add(videoStatus);gap(15);add(button("Zaustavi prikaz","camera-stop",()->devices(devices)));gap(12);add(text("Pomicanje kamere, mikrofon i arhiva snimki u pripremi su za podržane modele.",11,MUTED));
        try{vlc=new LibVLC(activity,new ArrayList<>(Arrays.asList("--rtsp-tcp","--network-caching=500","--no-metadata-network-access","--no-lua","--no-video-title-show","--quiet","--verbose=-1")));player=new MediaPlayer(vlc);player.getVLCVout().setVideoView(surface);player.getVLCVout().attachViews();surface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(player!=null)player.getVLCVout().setWindowSize(r-l,b-t);});player.setEventListener(event->{if(closed||suspended||videoStatus==null)return;if(event.type==MediaPlayer.Event.Vout&&event.getVoutCount()>0&&player!=null&&player.getVLCVout().areViewsAttached()){videoStatus.setText("Prijenos uživo");videoStatus.setTextColor(TEAL);}else if(event.type==MediaPlayer.Event.EncounteredError){videoStatus.setText("Kamera nije vratila sliku. Provjerite model i mrežu pa pokušajte ponovno.");videoStatus.setTextColor(AMBER);}});Media media=new Media(vlc,Uri.parse(uri));media.setHWDecoderEnabled(true,false);media.addOption(":rtsp-tcp");player.setMedia(media);media.release();player.play();}catch(Exception error){if(videoStatus!=null)videoStatus.setText("Izvorni video modul nije dostupan. Ponovno instalirajte aplikaciju.");}}
    private void stopVideo(){videoStatus=null;if(player!=null){player.setEventListener(null);player.stop();if(player.getVLCVout().areViewsAttached())player.getVLCVout().detachViews();player.release();player=null;}if(vlc!=null){vlc.release();vlc=null;}}
    private void failure(String message){base("Kamera trenutačno nije dostupna",message==null?"Provjerite prijavu i mrežnu vezu.":message);add(button("Pokušaj ponovno","camera-retry",this::start));gap(12);add(button("Nova prijava","camera-login-again",this::login));gap(12);add(button("Obriši spremljenu prijavu","camera-forget",this::forget));}
    private void forget(){engine.forget();devices=new JSONArray();account="";login();}
    public void onHostPause(){generation++;suspended=true;stopVideo();engine.stop();}
    public void onHostResume(){if(suspended&&!closed){suspended=false;busy=false;start();}}
    @Override public void close(){generation++;closed=true;main.removeCallbacksAndMessages(null);stopVideo();engine.close();work.shutdownNow();}
}
