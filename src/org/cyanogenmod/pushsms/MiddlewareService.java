package org.cyanogenmod.pushsms;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.telephony.ISms;
import com.android.internal.telephony.ISmsMiddleware;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;

import org.cyanogenmod.pushsms.bencode.BEncodedDictionary;
import org.cyanogenmod.pushsms.socket.SmsSocket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;

/**
 * Created by koush on 6/18/13.
 */
public class MiddlewareService extends android.app.Service {
    private static final String LOGTAG = "INTERCEPTOR";

    private final Handler handler = new Handler();
    private Hashtable<String, RegistrationFuture> numberToRegistration = new Hashtable<String, RegistrationFuture>();
//    private short smsPort;
    private RegistrationFuture selfRegistrationFuture = new RegistrationFuture();
    private GoogleCloudMessaging gcm;
    private ISms smsTransport;
    private Registry registry;
    private SharedPreferences settings;
    private SharedPreferences accounts;
    private KeyPair keyPair;
    private RSAPublicKeySpec rsaPublicKeySpec;
    String gcmApiKey;

    private static final String SERVER_API_URL = "https://cmmessaging.appspot.com/api/v1";
    private static final String GCM_URL = SERVER_API_URL + "/gcm";
    private static final String FIND_URL = SERVER_API_URL + "/find";

    private void registerGcm(final String senderId) {
        new Thread() {
            @Override
            public void run() {
                try {
                    gcm = GoogleCloudMessaging.getInstance(MiddlewareService.this);

                    final String r = gcm.register(senderId);
                    Registration self = new Registration();
                    self.registrationId = r;
                    selfRegistrationFuture.setComplete(self);
//                    numberToRegistration.put("2064951490", selfRegistrationFuture);
                    Log.i(LOGTAG, "Registration ID: " + r);
                } catch (IOException e) {
                    e.printStackTrace();
                    selfRegistrationFuture.setComplete(e);
                }
            }
        }.start();
    }

    private void getGcmInfo() {
        Ion.with(this)
        .load(GCM_URL)
        .asJsonObject()
        .setCallback(new FutureCallback<JsonObject>() {
            @Override
            public void onCompleted(Exception e, JsonObject result) {
                try {
                    if (e != null)
                        throw e;
                    String senderId = result.get("sender_id").getAsString();
                    gcmApiKey = result.get("api_key").getAsString();
                    settings.edit()
                    .putString("gcm_sender_id", senderId)
                    .putString("gcm_api_key", gcmApiKey)
                    .commit();
                    registerGcm(senderId);
                }
                catch (Exception ex) {
                    registerGcm(settings.getString("gcm_sender_id", "494395756847"));
                }
            }
        });
    }

    private void registerSmsMiddleware() {
        try {
            Class sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            smsTransport = ISms.Stub.asInterface((IBinder)getService.invoke(null, "isms"));
            smsTransport.registerSmsMiddleware("interceptor", stub);
        }
        catch (Exception e) {
            Log.e(LOGTAG, "register error", e);
        }
    }

    private void getOrCreateKeyPair() {
        String encodedKeyPair = settings.getString("keypair", null);
        if (encodedKeyPair != null) {
            try {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");

                ByteArrayInputStream bin = new ByteArrayInputStream(Base64.decode(encodedKeyPair, Base64.DEFAULT));
                ObjectInputStream in = new ObjectInputStream(bin);

                rsaPublicKeySpec = new RSAPublicKeySpec((BigInteger)in.readObject(), (BigInteger)(in.readObject()));
                RSAPrivateKeySpec rsaPrivateKeySpec = new RSAPrivateKeySpec((BigInteger)in.readObject(), (BigInteger)(in.readObject()));

                PublicKey pub = keyFactory.generatePublic(rsaPublicKeySpec);
                PrivateKey priv = keyFactory.generatePrivate(rsaPrivateKeySpec);

                keyPair = new KeyPair(pub, priv);
                return;
            }
            catch (Exception e) {
                Log.e(LOGTAG, "KeyPair load error", e);
            }
        }

        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            keyPair = gen.generateKeyPair();

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            rsaPublicKeySpec = keyFactory.getKeySpec(keyPair.getPublic(), RSAPublicKeySpec.class);
            RSAPrivateKeySpec privateKeySpec = keyFactory.getKeySpec(keyPair.getPrivate(), RSAPrivateKeySpec.class);

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bout);

            out.writeObject(rsaPublicKeySpec.getModulus());
            out.writeObject(rsaPublicKeySpec.getPublicExponent());

            out.writeObject(privateKeySpec.getModulus());
            out.writeObject(privateKeySpec.getPrivateExponent());

            out.flush();

            settings.edit().putString("keypair", Base64.encodeToString(bout.toByteArray(), Base64.DEFAULT)).commit();
            settings.edit().putBoolean("needs_register", true).commit();
        }
        catch (Exception e) {
            Log.e(LOGTAG, "KeyPair generation error", e);
            keyPair = null;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

//        smsPort = Short.valueOf(getString(R.string.sms_port));

        settings = getSharedPreferences("settings", MODE_PRIVATE);
        accounts = getSharedPreferences("accounts", MODE_PRIVATE);

        getOrCreateKeyPair();

        gcmApiKey = settings.getString("gcm_api_key", null);
        getGcmInfo();

        registerSmsMiddleware();

        registry = new Registry(this);
        registry.load(numberToRegistration);

        if (settings.getBoolean("needs_register", false))
            registerEndpoints();
    }

//    void sendRegistration(final String destAddr, final BEncodedDictionary payload) {
//        selfRegistrationFuture.addCallback(new FutureCallback<Registration>() {
//            @Override
//            public void onCompleted(Exception e, Registration registration) {
//                payload.put("r", registration.registrationId);
//                SmsSocket socket = findOrCreateSmsSocket(destAddr);
//                socket.write(new ByteBufferList(payload.toByteArray()));
//            }
//        });
//    }

    ISmsMiddleware.Stub stub = new ISmsMiddleware.Stub() {
        @Override
        public boolean onSendText(String destAddr, String scAddr, String text, PendingIntent sentIntent, PendingIntent deliveryIntent) throws RemoteException {
            List<PendingIntent> sentIntents = null;
            List<PendingIntent> deliveryIntents = null;
            if (sentIntent != null) {
                sentIntents = new ArrayList<PendingIntent>();
                sentIntents.add(sentIntent);
            }
            if (deliveryIntent != null) {
                deliveryIntents = new ArrayList<PendingIntent>();
                deliveryIntents.add(deliveryIntent);
            }

            ArrayList<String> texts = new ArrayList<String>();
            texts.add(text);
            return onSendMultipartText(destAddr, scAddr, texts, sentIntents, deliveryIntents, false);
        }

        @Override
        public boolean onSendMultipartText(String destAddr, String scAddr, List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents) throws RemoteException {
            return onSendMultipartText(destAddr, scAddr, texts, sentIntents, deliveryIntents, true);
        }

        public boolean onSendMultipartText(String destAddr, String scAddr, List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents, boolean multipart) throws RemoteException {
            if (keyPair == null)
                return false;

            if (!selfRegistrationFuture.isDone())
                return false;

            final GcmText sendText = new GcmText();
            sendText.destAddr = destAddr;
            sendText.scAddr = scAddr;
            sendText.texts.addAll(texts);
            sendText.multipart = multipart;

            RegistrationFuture future = findRegistration(destAddr);

            if (future != null && future.isDone()) {
                try {
                    if (!future.get().isRegistered())
                        return false;
                }
                catch (Exception e) {
                    return false;
                }
            }

            if (future == null)
                future = createRegistration(destAddr);


            future.addCallback(new FutureCallback<Registration>() {
                @Override
                public void onCompleted(Exception e, Registration result) {
                    if (e != null || !result.isRegistered()) {
                        sendText.manageFailure(sentIntents, deliveryIntents);
                        return;
                    }

                    sendText.send(MiddlewareService.this, gcmApiKey, result.registrationId, sentIntents, deliveryIntents);
                }
            });


            return true;


//            if (registration == Registry.NOT_REGISTERED)
//                return false;
//
//            final GcmText sendText = new GcmText();
//            sendText.destAddr = destAddr;
//            sendText.scAddr = scAddr;
//            sendText.texts.addAll(texts);
//            if (registration == null) {
//                final RegistrationFuture future = registration = new RegistrationFuture();
//                numberToRegistration.put(destAddr, registration);
//
//                // attempt to negotiate a registration id with the other end
//                BEncodedDictionary payload = new BEncodedDictionary();
//                payload.put("v", 1);
//                payload.put("t", "rr");
//                payload.put("y", destAddr);
//
//                sendRegistration(destAddr, payload);
//
//                handler.postDelayed(new Runnable() {
//                    @Override
//                    public void run() {
//                        if (!future.isDone())
//                            future.setComplete(new Exception("no response"));
//                    }
//                }, 300000);
//            }
//
//            registration.addCallback(new FutureCallback<Registration>() {
//                @Override
//                public void onCompleted(Exception e, Registration result) {
//                    if (e != null) {
//                        Log.e(LOGTAG, "registration exchange failed", e);
//                        handler.post(new Runnable() {
//                            @Override
//                            public void run() {
//                                Toast.makeText(MiddlewareService.this, "GCM Sms registration failure: " + sendText.destAddr, Toast.LENGTH_SHORT).show();
//                            }
//                        });
//                        registry.unregister(sendText.destAddr);
//                        numberToRegistration.put(sendText.destAddr, Registry.NOT_REGISTERED);
//                        sendText.manageFailure();
//                        return;
//                    }
//
//                    Log.i(LOGTAG, "registration exchange succeeded");
//                    handler.post(new Runnable() {
//                        @Override
//                        public void run() {
//                            Toast.makeText(MiddlewareService.this, "GCM Sms sent to " + sendText.destAddr, Toast.LENGTH_SHORT).show();
//                        }
//                    });
//                    sendText.send(MiddlewareService.this, gcmApiKey, result.registrationId, sentIntents, deliveryIntents);
//                }
//            });
        }
    };

    private RegistrationFuture createRegistration(String address) {

        final RegistrationFuture ret = new RegistrationFuture();

        JsonObject post = new JsonObject();
        JsonArray authorities = new JsonArray();
        HashSet<String> emailHash = new HashSet<String>();
        post.add("authorities", authorities);
        post.addProperty("endpoint", address);

        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address));
        Cursor c = getContentResolver().query(uri, new String[]{ContactsContract.PhoneLookup._ID }, null, null, null);

        while (c != null && c.moveToNext()) {
            Cursor emailCursor = getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[] {ContactsContract.CommonDataKinds.Email.ADDRESS },
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?",
                    new String[] { String.valueOf(c.getLong(c.getColumnIndex(ContactsContract.PhoneLookup._ID)))},
                    null
            );
            while (emailCursor != null && emailCursor.moveToNext()) {
                try {
                    MessageDigest digest = MessageDigest.getInstance("MD5");
                    String email = emailCursor.getString(emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS));
                    String authority = Base64.encodeToString(digest.digest(("email:" + email).getBytes()), Base64.NO_WRAP);
                    emailHash.add(authority);
                }
                catch (Exception e) {
                }
            }
            if (emailCursor != null)
                emailCursor.close();
        }
        if (c != null)
            c.close();

        if (emailHash.size() == 0) {
            ret.setComplete(new Exception("no emails"));
            return ret;
        }

        for (String authority: emailHash) {
            authorities.add(new JsonPrimitive(authority));
        }

        Ion.with(this)
                .load(FIND_URL)
                .setJsonObjectBody(post)
                .asJsonObject().setCallback(new FutureCallback<JsonObject>() {
            @Override
            public void onCompleted(Exception e, JsonObject result) {
                try {
                    if (e != null)
                        throw e;
                    if (result.has("error"))
                        throw new Exception(result.toString());

                    Registration registration = new Registration();
                    registration.registrationId = result.get("registration_id").getAsString();
                    BigInteger publicExponent = new BigInteger(Base64.decode(result.get("public_exponent").getAsString(), Base64.DEFAULT));
                    BigInteger publicModulus = new BigInteger(Base64.decode(result.get("public_modulus").getAsString(), Base64.DEFAULT));
                    RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(publicModulus, publicExponent);
                    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                    registration.remotePublicKey = keyFactory.generatePublic(publicKeySpec);
                    registration.date = System.currentTimeMillis();

                    ret.setComplete(registration);
                }
                catch (Exception ex) {
                    ret.setComplete(ex);
                }
            }
        });

        return ret;
    }

    private RegistrationFuture findRegistration(String address) {
        for (String number: numberToRegistration.keySet()) {
            if (PhoneNumberUtils.compare(number, address)) {
                return numberToRegistration.get(number);
            }
        }

        return null;
    }
//
//    private SmsSocket findOrCreateSmsSocket(String number) {
//        for (SmsSocket smsSocket: smsSockets) {
//            if (PhoneNumberUtils.compare(this, number, smsSocket.getNumber()))
//                return smsSocket;
//        }
//
//        final SmsSocket ret = new SmsSocket(this, Ion.getDefault(this).getServer(), number, smsPort);
//        smsSockets.add(ret);
//
//        ret.setDataCallback(new DataCallback() {
//            @Override
//            public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
//                try {
//                    BEncodedDictionary payload = BEncodedDictionary.parseDictionary(bb.getAll());
//                    String t = payload.getString("t");
//                    if (t == null)
//                        return;
//                    if ("rr".equals(t)) {
//                        // if the requester is the this device, just respond right away.
//                        String requester = payload.getString("y");
//                        if (PhoneNumberUtils.compare(requester, ret.getNumber())) {
//                            BEncodedDictionary response = new BEncodedDictionary();
//                            response.put("v", 1);
//                            response.put("t", "r");
//                            // let the requester know who we think they are.
//                            response.put("y", ret.getNumber());
//                            sendRegistration(ret.getNumber(), response);
//                            return;
//                        }
//                    }
//
//                    String r = payload.getString("r");
//                    if (r == null)
//                        return;
//
//                    RegistrationFuture rf = findRegistration(ret.getNumber());
//
//                    // no registration or new registration, let's set up listeners
//                    if (rf == null || rf.isDone() || rf.start < System.currentTimeMillis() - 300000L) {
//                        rf = new RegistrationFuture();
//                        numberToRegistration.put(ret.getNumber(), rf);
//                    }
//
//                    handler.post(new Runnable() {
//                        @Override
//                        public void run() {
//                            Toast.makeText(MiddlewareService.this, "GCM Sms with " + ret.getNumber() + " enabled", Toast.LENGTH_SHORT).show();
//                        }
//                    });
//                    Registration registration = new Registration();
//                    registration.registrationId = r;
//                    rf.setComplete(registration);
//                    registry.register(ret.getNumber(), r);
//                }
//                catch (Exception e) {
//                }
//            }
//        });
//        return ret;
//    }
//
//    ArrayList<SmsSocket> smsSockets = new ArrayList<SmsSocket>();

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent != null) {
//            if ("android.intent.action.DATA_SMS_RECEIVED".equals(intent.getAction())) {
//                try {
//                    Bundle bundle = intent.getExtras();
//                    Object[] pdusObj = (Object[]) bundle.get("pdus");
//                    for (int i = 0; i < pdusObj.length; i++) {
//                        SmsMessage message = SmsMessage.createFromPdu((byte[]) pdusObj[i]);
//
//                        SmsSocket smsSocket = findOrCreateSmsSocket(message.getOriginatingAddress());
//
//                        smsSocket.onMessage(message);
//                    }
//                }
//                catch (Exception e) {
//                    e.printStackTrace();
//                }
//                return START_STICKY;
//            }
//            else if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
//                return START_STICKY;
//            }
//            else
            if ("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())) {
                String messageType = gcm.getMessageType(intent);
                if (GoogleCloudMessaging.MESSAGE_TYPE_SEND_ERROR.equals(messageType)) {
                } else if (GoogleCloudMessaging.MESSAGE_TYPE_DELETED.equals(messageType)) {
                } else {
                    try {
                        String data = intent.getStringExtra("bencoded");
                        GcmText gcmText = GcmText.parse(data);
                        if (gcmText != null) {
                            Toast.makeText(this, "GCM Sms from " + gcmText.destAddr + " received", Toast.LENGTH_SHORT).show();
                            smsTransport.synthesizeMessages(gcmText.destAddr, gcmText.scAddr, gcmText.texts, System.currentTimeMillis());
                        }
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            else if (ACTION_REGISTER.equals(intent.getAction())) {
                registerEndpoints();
            }
        }


        return START_STICKY;
    }

    public static final String ACTION_REGISTER = "org.cyanogenmod.intent.action.REGISTER";

    private String getNumber() {
        final TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        String ret = settings.getString("phone_number", tm.getLine1Number());
        if (TextUtils.isEmpty(ret))
            ret = tm.getLine1Number();
        return ret;
    }

    void withRegistration(Exception e, final Registration registration) {
        if (registering || e != null)
            return;
        registering = true;
        JsonObject post = new JsonObject();
        post.addProperty("registration_id", registration.registrationId);
        JsonObject tokens = new JsonObject();
        post.add("access_tokens", tokens);
        for (Account account : AccountManager.get(MiddlewareService.this).getAccountsByType("com.google")) {
            if (accounts.getAll().containsKey(account.name)) {
                try {
                    String token = GoogleAuthUtil.getToken(MiddlewareService.this, account.name, "oauth2:https://www.googleapis.com/auth/userinfo.email");
                    tokens.addProperty(token, accounts.getBoolean(account.name, false));
                }
                catch (Exception ex) {
                    Log.e(LOGTAG, "token error", ex);
                }
            }
        }
        post.addProperty("endpoint", getNumber());
        post.addProperty("public_modulus", Base64.encodeToString(rsaPublicKeySpec.getModulus().toByteArray(), Base64.DEFAULT));
        post.addProperty("public_exponent", Base64.encodeToString(rsaPublicKeySpec.getPublicExponent().toByteArray(), Base64.DEFAULT));

        Ion.with(MiddlewareService.this)
        .load("https://cmmessaging.appspot.com/api/v1/register")
        .setJsonObjectBody(post)
        .asJsonObject()
        .setCallback(new FutureCallback<JsonObject>() {
            @Override
            public void onCompleted(Exception e, JsonObject result) {
                registering = false;
                if (e != null || result.has("error"))
                    return;
                settings.edit().putBoolean("needs_register", false).commit();
            }
        });
    };

    boolean registering = false;
    private void registerEndpoints() {
        selfRegistrationFuture.addCallback(new FutureCallback<Registration>() {
            @Override
            public void onCompleted(final Exception e, final Registration result) {
                new Thread() {
                    @Override
                    public void run() {
                        withRegistration(e, result);
                    }
                }.start();
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}