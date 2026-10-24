package id.nusastudio.vault;

import android.app.*;
import android.app.job.*;
import android.content.*;
import java.util.Arrays;
import org.json.JSONObject;

public class BackupJob extends JobService {
  public static final int JOB_ID = 701;
  private Thread worker;

  public static void schedule(Context context, boolean wifiOnly) {
    JobScheduler scheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
    int result =
        scheduler.schedule(
            new JobInfo.Builder(JOB_ID, new ComponentName(context, BackupJob.class))
                .setRequiredNetworkType(
                    wifiOnly ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build());
    if (result != JobScheduler.RESULT_SUCCESS)
      throw new IllegalStateException("Jadwal backup ditolak Android");
  }

  public static void cancel(Context context) {
    ((JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE)).cancel(JOB_ID);
  }

  @Override
  public boolean onStartJob(JobParameters params) {
    worker =
        new Thread(
            () -> {
              boolean retry = false;
              char[] pass = null;
              PrivateSettings settings = new PrivateSettings(this);
              try {
                JSONObject config = settings.read();
                if (!config.optBoolean("automatic")) {
                  jobFinished(params, false);
                  return;
                }
                pass = config.getString("backgroundPassword").toCharArray();
                VaultStore store = new VaultStore(this);
                store.unlock(pass);
                int sourceFailures = BackupPlan.scan(this, store, pass);
                int count =
                    new RelayClient(
                            config.getString("relay"),
                            config.getString("token"),
                            config.optString("relayCa"))
                        .backup(store, pass);
                if (sourceFailures > 0)
                  throw new java.io.IOException(
                      count
                          + " file tersalin tetapi "
                          + sourceFailures
                          + " sumber perlu diperiksa");
                settings.log("Backup otomatis selesai: " + count + " file");
                notifyResult("Backup selesai", count + " file tersimpan di relay pribadi.");
              } catch (Exception e) {
                retry = !Thread.currentThread().isInterrupted();
                settings.log(
                    "Backup otomatis gagal; periksa koneksi, izin folder, dan pengaturan relay");
                notifyResult(
                    "Backup belum selesai", "Buka vault untuk memeriksa koneksi dan izin folder.");
              } finally {
                if (pass != null) Arrays.fill(pass, '\0');
              }
              jobFinished(params, retry);
            },
            "nusa-backup");
    worker.start();
    return true;
  }

  private void notifyResult(String title, String text) {
    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    manager.createNotificationChannel(
        new NotificationChannel(
            "backup", "Aktivitas backup pribadi", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open =
        PendingIntent.getActivity(
            this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    try {
      manager.notify(
          701,
          new Notification.Builder(this, "backup")
              .setSmallIcon(id.nusastudio.vault.R.drawable.ic_nusa)
              .setContentTitle(title)
              .setContentText(text)
              .setContentIntent(open)
              .setAutoCancel(true)
              .build());
    } catch (SecurityException ignored) {
    }
  }

  @Override
  public boolean onStopJob(JobParameters params) {
    if (worker != null) worker.interrupt();
    return true;
  }
}
