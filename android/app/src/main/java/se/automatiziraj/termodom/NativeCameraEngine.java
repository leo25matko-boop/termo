package se.automatiziraj.termodom;

import android.content.Context;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import okhttp3.*;

/** Private bundled camera engine. Xiaomi credentials only travel to the original Xiaomi HTTPS service. */
public final class NativeCameraEngine implements AutoCloseable {
    public static final class Challenge extends Exception {
        public final JSONObject data;
        Challenge(JSONObject data){super("Xiaomi traži dodatnu potvrdu prijave.");this.data=data;}
    }
    private static final int LIMIT=2_000_000;
    private static final String KEY="TermoDom.CameraSession.v1";
    private final Context context;
    private final File directory,session;
    private final OkHttpClient client=new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).connectTimeout(3,TimeUnit.SECONDS).readTimeout(55,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private Process process;
    private String authorization,rtspPassword,streamName;
    private int apiPort,rtspPort;
    private volatile boolean closing;
    public NativeCameraEngine(Context context){this.context=context.getApplicationContext();directory=new File(context.getNoBackupFilesDir(),"camera");session=new File(directory,"session.enc");}
    private static String secret(){byte[] bytes=new byte[24];new SecureRandom().nextBytes(bytes);return Base64.encodeToString(bytes,Base64.NO_WRAP|Base64.URL_SAFE|Base64.NO_PADDING);}
    private static int port() throws IOException {try(ServerSocket socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){return socket.getLocalPort();}}
    private static void write(File file,byte[] data) throws IOException {try(FileOutputStream out=new FileOutputStream(file)){out.write(data);}file.setReadable(false,false);file.setWritable(false,false);file.setReadable(true,true);file.setWritable(true,true);}
    private static byte[] read(File file) throws IOException {try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>LIMIT)throw new IOException("Prevelika datoteka sesije.");out.write(buffer,0,n);}return out.toByteArray();}}
    private SecretKey key() throws Exception {KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);if(store.containsAlias(KEY))return ((KeyStore.SecretKeyEntry)store.getEntry(KEY,null)).getSecretKey();KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();}
    private void saveSession() throws Exception {byte[] plain=call("GET","/api/termodom/session",null).getBytes(StandardCharsets.UTF_8);new JSONObject(new String(plain,StandardCharsets.UTF_8));Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());byte[] encrypted=cipher.doFinal(plain),iv=cipher.getIV();ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(iv.length);out.write(iv);out.write(encrypted);write(session,out.toByteArray());Arrays.fill(plain,(byte)0);}
    private byte[] restoreSession() throws Exception {if(!session.isFile())return "{}".getBytes(StandardCharsets.UTF_8);byte[] encrypted=read(session);int length=encrypted.length==0?0:encrypted[0]&255;if(length!=12||encrypted.length<length+18)throw new IOException("Neispravna sesija kamere.");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,encrypted,1,length));return cipher.doFinal(encrypted,1+length,encrypted.length-1-length);}
    public synchronized void start() throws Exception {
        if(process!=null&&process.isAlive())return;closing=false;if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Nije moguće pripremiti kameru.");
        File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libgo2rtc.so");if(!binary.isFile())throw new IOException("U instalaciji nedostaje izvorni modul kamere.");
        apiPort=port();do{rtspPort=port();}while(apiPort==rtspPort);String password=secret();rtspPassword=secret();authorization=Credentials.basic("termodom",password);streamName="camera_"+secret();
        byte[] restored=restoreSession();JSONObject tokens=new JSONObject(new String(restored,StandardCharsets.UTF_8));Arrays.fill(restored,(byte)0);
        JSONObject settings=new JSONObject().put("api",new JSONObject().put("listen","127.0.0.1:"+apiPort).put("username","termodom").put("password",password).put("local_auth",true).put("allow_paths",new JSONArray().put("/api").put("/api/xiaomi").put("/api/streams").put("/api/termodom/session")))
                .put("rtsp",new JSONObject().put("listen","127.0.0.1:"+rtspPort).put("username","termodom").put("password",rtspPassword)).put("webrtc",new JSONObject().put("listen","")).put("srt",new JSONObject().put("listen","")).put("homekit",new JSONObject().put("listen","")).put("log",new JSONObject().put("level","disabled")).put("xiaomi",tokens);
        ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"-config","-");builder.directory(directory);builder.redirectErrorStream(true);builder.environment().put("HOME",directory.getAbsolutePath());builder.environment().put("TMPDIR",context.getCacheDir().getAbsolutePath());builder.environment().put("LD_LIBRARY_PATH",context.getApplicationInfo().nativeLibraryDir);process=builder.start();try(OutputStream pipe=process.getOutputStream()){pipe.write(settings.toString().getBytes(StandardCharsets.UTF_8));}
        final Process child=process;Thread drain=new Thread(()->{try(InputStream input=child.getInputStream()){byte[] bytes=new byte[4096];while(input.read(bytes)!=-1){/* Never log credentials, camera URLs, or cloud responses. */}}catch(IOException ignored){}},"TermoDom-camera-engine");drain.setDaemon(true);drain.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);Exception failure=null;boolean ready=false;
        while(System.nanoTime()<deadline&&!closing){if(!child.isAlive())throw new IOException("Izvorni modul kamere nije pokrenut.");try{call("GET","/api/termodom/session",null);ready=true;break;}catch(Exception error){failure=error;Thread.sleep(100);}}
        if(ready){try{requireIsolation();return;}catch(Exception isolation){stop();throw new IOException("Provjera zaštite lokalnog prijenosa nije prošla.",isolation);}}
        if(BuildConfig.DEBUG)android.util.Log.e("TermoDomCamera","Native engine health failed before account login: "+(failure==null?"no response":failure.getClass().getSimpleName()));
        stop();throw new IOException("Kamera nije pokrenuta. Pokušajte ponovno.",failure);
    }
    private String call(String method,String path,RequestBody body) throws Exception {
        if(closing||process==null||!process.isAlive())throw new IOException("Modul kamere nije aktivan.");
        if(!path.startsWith("/api/xiaomi")&&!path.startsWith("/api/streams")&&!path.equals("/api/termodom/session"))throw new IOException("Nepodržana funkcija kamere.");
        Request.Builder request=new Request.Builder().url("http://127.0.0.1:"+apiPort+path).header("Authorization",authorization);if(method.equals("GET"))request.get();else request.method(method,body==null?RequestBody.create(new byte[0],null):body);
        try(Response response=client.newCall(request.build()).execute()){ResponseBody content=response.body();if(content==null)throw new IOException("Modul kamere nije vratio odgovor.");byte[] bytes;try(InputStream in=content.byteStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>LIMIT)throw new IOException("Odgovor kamere je prevelik.");out.write(buffer,0,n);}bytes=out.toByteArray();}String text=new String(bytes,StandardCharsets.UTF_8);
            if(response.code()==401&&path.equals("/api/xiaomi")&&method.equals("POST")){try{throw new Challenge(new JSONObject(text));}catch(org.json.JSONException invalid){throw new IOException("Xiaomi je odbio prijavu. Provjerite podatke i pokušajte ponovno.");}}
            if(response.code()==404&&method.equals("GET")&&path.startsWith("/api/xiaomi?id="))return "{\"sources\":[]}";
            if(!response.isSuccessful())throw new IOException(path.startsWith("/api/streams")?"Kamera nije uspostavila podržani prijenos. Provjerite model, mrežu i dopuštenje na Xiaomi računu.":"Xiaomi nije prihvatio zahtjev. Provjerite prijavu, regiju i internetsku vezu.");return text;
        }
    }
    /** Fail closed if either child listener would expose a session without credentials. */
    private void requireIsolation() throws Exception {
        Request anonymous=new Request.Builder().url("http://127.0.0.1:"+apiPort+"/api/xiaomi").build();try(Response response=client.newCall(anonymous).execute()){if(response.code()!=401)throw new IOException("Zaštita lokalnog modula kamere nije aktivna (HTTP "+response.code()+").");}
        try(Socket socket=new Socket()){socket.connect(new InetSocketAddress("127.0.0.1",rtspPort),2000);socket.setSoTimeout(2000);OutputStream out=socket.getOutputStream();out.write(("DESCRIBE rtsp://127.0.0.1:"+rtspPort+"/"+streamName+" RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.flush();BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String line=input.readLine();if(line==null||!line.startsWith("RTSP/1.0 401"))throw new IOException("Zaštita prijenosa kamere nije aktivna ("+(line==null?"nema odgovora":line.split(" ").length>1?line.split(" ")[1]:"neispravan status")+").");}
    }
    public synchronized String verifyIsolation() throws Exception {start();requireIsolation();return "TERMODOM_CAMERA_ENGINE_OK: native child; HTTP401; RTSP401; private pipe; no cloud request";}
    public synchronized JSONArray accounts() throws Exception {start();String body=call("GET","/api/xiaomi",null);return body.trim().equals("null")?new JSONArray():new JSONArray(body);}
    public synchronized void login(String username,String password) throws Exception {start();call("POST","/api/xiaomi",new FormBody.Builder().add("username",username).add("password",password).build());saveSession();}
    public synchronized void challenge(String name,String value) throws Exception {if(!name.equals("captcha")&&!name.equals("verify"))throw new IllegalArgumentException();call("POST","/api/xiaomi",new FormBody.Builder().add(name,value).build());saveSession();}
    public synchronized JSONArray cameras(String account,String region) throws Exception {start();String body=call("GET","/api/xiaomi?id="+Uri.encode(account)+"&region="+Uri.encode(region),null);JSONObject response=new JSONObject(body);JSONArray sources=response.optJSONArray("sources");if(sources==null)sources=response.optJSONArray("items");return sources==null?new JSONArray():sources;}
    public synchronized String play(String source) throws Exception {start();Uri uri=Uri.parse(source);if(!"xiaomi".equals(uri.getScheme())||uri.getQueryParameter("did")==null||uri.getQueryParameter("model")==null)throw new IOException("Xiaomi nije vratio valjanu kameru.");call("PUT","/api/streams?name="+Uri.encode(streamName)+"&src="+Uri.encode(source),null);call("GET","/api/streams?src="+Uri.encode(streamName)+"&video",null);return "rtsp://termodom:"+rtspPassword+"@127.0.0.1:"+rtspPort+"/"+streamName+"?video&audio";}
    public synchronized void forget(){stop();session.delete();}
    public void stop(){closing=true;client.dispatcher().cancelAll();Process child=process;process=null;if(child!=null)child.destroy();authorization=null;rtspPassword=null;streamName=null;}
    @Override public void close(){stop();}
}
