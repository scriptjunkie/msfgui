package msfgui;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.msgpack.MessagePack;
import org.msgpack.packer.Packer;
import org.msgpack.type.*;

/**
 * Implements an RPC backend using the MessagePack interface
 * @author scriptjunkie
 */
public class MsgRpc extends RpcConnection {
	private URL u;
	private URLConnection huc; // new for each call
	protected int timeout = 5000;

	/**
	 * Creates a new URL to use as the basis of a connection.
	 */
	protected void connect() throws MalformedURLException{
		if(ssl){ // Install the all-trusting trust manager & HostnameVerifier
			try {
				SSLContext sc = SSLContext.getInstance("SSL");
				sc.init(null, new TrustManager[]{
						new X509TrustManager() {
							public java.security.cert.X509Certificate[] getAcceptedIssuers() {
								return null;
							}
							public void checkClientTrusted(
								java.security.cert.X509Certificate[] certs, String authType) {
							}
							public void checkServerTrusted(
								java.security.cert.X509Certificate[] certs, String authType) {
							}
						}
					}, new java.security.SecureRandom());
				HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
				HttpsURLConnection.setDefaultHostnameVerifier( new HostnameVerifier(){
					public boolean verify(String string,SSLSession ssls) {
						return true;
					}
				});
			} catch (Exception e) {
			}
			u = new URL("https",host,port,"/api/1.0");
		}else{
			u = new URL("http",host,port,"/api/1.0");
		}
	}

	/**
	 * Decodes a response recursively from MessagePackObject to a normal Java object
	 * @param src MessagePack response
	 * @return decoded object
	 */
	private static Object unMsg(Value src){
		Object out = src;
		if(src.isArrayValue()){
			ArrayValue l = src.asArrayValue();
			List outList = new ArrayList(l.size());
			out = outList;
			for(Value o : l)
				outList.add(unMsg(o));
		}else if(src.isBooleanValue()){
			out = src.asBooleanValue().getBoolean();
		}else if(src.isFloatValue()){
			out = src.asFloatValue().getFloat();
		}else if(src.isIntegerValue()) {
			out = src.asIntegerValue().getInt();
		}else if(src.isMapValue()){
			Set<Entry<Value,Value>> ents = src.asMapValue().entrySet();
			out = new HashMap();
			for (Entry<Value,Value> ent : ents){
				Object key = unMsg(ent.getKey());
				Value val = ent.getValue();
				Object valo;
				// Hack - keep bytes of generated or encoded payload
				if(ents.size() == 1 && val.isRawValue() &&
						(key.equals("payload") || key.equals("encoded")))
					valo = val.asRawValue().getByteArray();
				else
					valo = unMsg(val);
				((Map)out).put(key, valo);
			}
			if(((Map)out).containsKey("error") && ((Map)out).containsKey("error_class")){
				System.out.println(((Map)out).get("error_backtrace"));
				throw new MsfException(((Map)out).get("error_message").toString());
			}
		}else if(src.isNilValue()){
			out = null;
		}else if(src.isRawValue()){
			out = src.asRawValue().getString();
		}
		return out;
	}

	/** Creates an XMLRPC call from the given method name and parameters and sends it */
	protected void writeCall(String methodName, Object[] args) throws Exception{
		huc = u.openConnection();
		huc.setDoOutput(true);
		huc.setDoInput(true);
		huc.setUseCaches(false);
		huc.setRequestProperty("Content-Type", "binary/message-pack");
		huc.setReadTimeout(timeout);
		OutputStream os = huc.getOutputStream();
		MessagePack mp = new MessagePack();
		Packer pk = mp.createPacker(os);

		pk.writeArrayBegin(args.length+1);
		pk.write(methodName);
		for(Object o : args)
			pk.write(o);
		pk.writeArrayEnd();
		os.close();
	}

	/** Receives an RPC response and converts to an object */
	protected Object readResp() throws Exception{
		InputStream is = huc.getInputStream();
		MessagePack mp = new MessagePack();
		Value val = mp.createUnpacker(is).readValue();
		return unMsg(val);
	}
}
