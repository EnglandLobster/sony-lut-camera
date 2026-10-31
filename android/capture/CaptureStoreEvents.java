package org.hongen.sonylutprobe;

import java.lang.reflect.*;

/** Resolve the storage callback by capability, not Android or camera model. */
final class CaptureStoreEvents {
    interface Sink {
        void named(Object status,String directory,String name);
        void failed(Throwable error);
    }
    private final Object owner;
    private final Method setter;
    private final boolean named;
    private volatile boolean closed;

    CaptureStoreEvents(final Object owner,final Sink sink)throws Exception {
        this.owner=owner;
        Class<?> camera=owner.getClass(),listener;
        Method method;
        try{
            listener=Class.forName(camera.getName()+"$StoreImageCompleteListener",false,camera.getClassLoader());
            method=camera.getMethod("setStoreImageCompleteListener",listener);
        }catch(ClassNotFoundException missing){
            setter=null;named=false;return;
        }catch(NoSuchMethodException missing){
            setter=null;named=false;return;
        }
        setter=method;named=true;
        Object callback=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},new InvocationHandler(){
            public Object invoke(Object proxy,Method method,Object[] args){
                if(method.getDeclaringClass()==Object.class){
                    if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
                    if(method.getName().equals("equals"))return proxy==args[0];
                    return "LutStoreEvents";
                }
                if(closed)return null;
                try{
                    Object info=args[1];
                    sink.named(args[0],(String)info.getClass().getField("DirectoryName").get(info),
                        (String)info.getClass().getField("FileName").get(info));
                }catch(Throwable error){sink.failed(error);}
                return null;
            }
        });
        // A setter failure is an actual runtime failure, not evidence that the
        // API is absent. Do not silently switch callback protocols in that case.
        setter.invoke(owner,callback);
    }
    boolean hasNamedReceipt(){return named;}
    void close()throws Exception{closed=true;if(setter!=null)setter.invoke(owner,new Object[]{null});}
}
