package com.anand.vault;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** Runs about once an hour: checks for updates/messages and continues the Google backup. */
public class VaultWorker extends Worker {
    public VaultWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        Updates.refresh(c, true);
        if (Vault.get(c).isGoogle() && !isStopped()) Sync.get(c).runAndWait();
        return Result.success();
    }

    @Override
    public void onStopped() {
        Sync.get(getApplicationContext()).stop();
    }

    static void schedule(Context c) {
        Constraints constraints = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(VaultWorker.class, 1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("vault-hourly", ExistingPeriodicWorkPolicy.KEEP, req);
    }
}
