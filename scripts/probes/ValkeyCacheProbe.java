import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs against the exact application image with its production TLS/ACL transport. No secrets in output. */
public class ValkeyCacheProbe {
    static int checks;
    static final String PREFIX="brain:public-search:v1:";
    static Class<?> type;
    static Object invoke(Object target,String method,Class<?>[] args,Object... values)throws Exception {
        Method m=target.getClass().getDeclaredMethod(method,args);m.setAccessible(true);
        try{return m.invoke(target,values);}catch(InvocationTargetException e){throw (Exception)e.getCause();}
    }
    static Object transport(String host,Path directory)throws Exception {
        var c=type.getDeclaredConstructor(String.class,int.class,Path.class);c.setAccessible(true);
        return c.newInstance(host,6379,directory);
    }
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    interface Attempt {void run()throws Exception;}
    static void denied(Attempt run,String expected)throws Exception {
        try{run.run();throw new AssertionError("Expected denial: "+expected);}
        catch(AssertionError e){throw e;}
        catch(Exception e){
            var names=new ArrayList<String>();for(Throwable t=e;t!=null;t=t.getCause())names.add(t.getClass().getSimpleName());
            require(names.stream().anyMatch(n->n.contains(expected)),"Unexpected failure class for "+expected);
        }
    }
    static Path copy(Path source)throws Exception {
        Path p=Files.createTempDirectory("cache-probe-");
        for(String name:List.of("ca.crt","username","password"))Files.copy(source.resolve(name),p.resolve(name));return p;
    }
    static void close(Object t)throws Exception {invoke(t,"close",new Class<?>[]{});}
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("network-denied")) {
            try(var socket=new Socket()) {
                try{socket.connect(new InetSocketAddress("brain-valkey.brain-data.svc",6379),1000);throw new AssertionError("Unexpected network access");}
                catch(SocketTimeoutException|ConnectException denied){System.out.println("PASS unrelated workload denied by cache NetworkPolicy");return;}
            }
        }
        type=Class.forName("io.knowledgebrain.brainquery.ValkeyPublicSearchTransport");
        Path directory=Path.of("/public-cache");String host="brain-valkey.brain-data.svc";
        String key=PREFIX+"f".repeat(64);Object t=transport(host,directory);
        try {
            byte[] value="disposable-probe".getBytes(StandardCharsets.UTF_8);
            invoke(t,"write",new Class<?>[]{String.class,byte[].class,int.class},key,value,5);
            require(Arrays.equals(value,(byte[])invoke(t,"read",new Class<?>[]{String.class},key)),"TLS round trip");
            denied(()->invoke(t,"read",new Class<?>[]{String.class},"foreign-key"),"IllegalArgumentException");
            denied(()->invoke(t,"write",new Class<?>[]{String.class,byte[].class,int.class},key,new byte[16385],5),"IllegalArgumentException");
            denied(()->invoke(t,"write",new Class<?>[]{String.class,byte[].class,int.class},key,value,0),"IllegalArgumentException");
            Field f=type.getDeclaredField("pool");f.setAccessible(true);Object pool=f.get(t);
            Object client=pool.getClass().getMethod("getResource").invoke(pool);
            try {
                Class<?> cls=client.getClass();
                for(String method:List.of("flushAll","aclList"))
                    denied(()->{try{cls.getMethod(method).invoke(client);}catch(InvocationTargetException e){throw (Exception)e.getCause();}},"JedisAccessControlException");
                for(String method:List.of("get","keys"))
                    denied(()->{try{cls.getMethod(method,String.class).invoke(client,key);}catch(InvocationTargetException e){throw (Exception)e.getCause();}},"JedisAccessControlException");
                denied(()->{try{cls.getMethod("set",String.class,String.class).invoke(client,"foreign-key","value");}catch(InvocationTargetException e){throw (Exception)e.getCause();}},"JedisAccessControlException");
                // Deliberately corrupt only the disposable probe key to verify bounded network reads.
                cls.getMethod("set",String.class,String.class).invoke(client,key,"x".repeat(32768));
                byte[] capped=(byte[])invoke(t,"read",new Class<?>[]{String.class},key);
                require(capped.length==16385,"GETRANGE network allocation cap");
            } finally {((AutoCloseable)client).close();}
            invoke(t,"write",new Class<?>[]{String.class,byte[].class,int.class},key,value,5);
            Thread.sleep(5200);
            require(((byte[])invoke(t,"read",new Class<?>[]{String.class},key)).length==0,"Server TTL expiry");
        } finally {close(t);}
        // DNS resolves to the same server IP but the certificate deliberately contains no IP SAN.
        Object wrongHost=transport(InetAddress.getByName(host).getHostAddress(),directory);
        try{denied(()->invoke(wrongHost,"read",new Class<?>[]{String.class},key),"SSLHandshakeException");}finally{close(wrongHost);}
        Path wrongCa=copy(directory);Files.copy(Path.of("/probe/wrong-ca.crt"),wrongCa.resolve("ca.crt"),StandardCopyOption.REPLACE_EXISTING);
        Object untrusted=transport(host,wrongCa);
        try{denied(()->invoke(untrusted,"read",new Class<?>[]{String.class},key),"SSLHandshakeException");}finally{close(untrusted);}
        Path wrongPassword=copy(directory);Files.writeString(wrongPassword.resolve("password"),"x".repeat(43));
        Object unauthenticated=transport(host,wrongPassword);
        try{denied(()->invoke(unauthenticated,"read",new Class<?>[]{String.class},key),"JedisAccessControlException");}finally{close(unauthenticated);}
        try(var plain=new Socket()){
            plain.connect(new InetSocketAddress(host,6379),1000);plain.setSoTimeout(1000);
            plain.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            try{require(plain.getInputStream().read()==-1,"Plaintext rejected");}catch(java.net.SocketException reset){checks++;}
        }
        System.out.println("PASS exact-image production transport: "+checks+" TLS/CA/hostname/ACL/bounds/TTL/plaintext checks");
    }
}
