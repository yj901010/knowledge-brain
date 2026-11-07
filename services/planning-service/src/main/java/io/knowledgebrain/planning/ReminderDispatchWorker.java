package io.knowledgebrain.planning;
import java.time.Instant;import org.springframework.context.SmartLifecycle;import io.micrometer.core.instrument.MeterRegistry;import org.slf4j.LoggerFactory;
final class ReminderDispatchWorker implements SmartLifecycle {
 private final ReminderDeliveryStore store;private final MeterRegistry metrics;private volatile boolean running;private volatile Thread worker;private volatile Instant lastSuccess;
 ReminderDispatchWorker(ReminderDeliveryStore store,MeterRegistry metrics){this.store=store;this.metrics=metrics;}
 public void start(){if(running)return;running=true;worker=Thread.ofVirtual().name("planning-reminder-dispatch").start(()->{while(running){try{boolean complete=true;for(var id:store.candidates(100)){try{if(store.process(id))metrics.counter("brain.planning.delivery.processed").increment();}catch(RuntimeException e){complete=false;failure(e);}}if(complete)lastSuccess=Instant.now();}catch(RuntimeException e){failure(e);}try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}}});}
 private void failure(RuntimeException e){metrics.counter("brain.planning.delivery.failures").increment();LoggerFactory.getLogger(getClass()).warn("reminder_dispatch retry failure={}; canonical pending intent retained",e.getClass().getSimpleName());}
 boolean ready(){return running&&lastSuccess!=null&&lastSuccess.isAfter(Instant.now().minusSeconds(20));}
 public boolean isRunning(){return running;}
 public void stop(){running=false;if(worker!=null){worker.interrupt();try{worker.join(15000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
}
