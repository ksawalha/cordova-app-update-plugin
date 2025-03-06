package com.mrspark.cordova.plugin;

import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.gms.tasks.Task;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaWebView;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import com.google.android.material.snackbar.Snackbar;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public class UpdatePlugin extends CordovaPlugin {
    public static final int REQUEST_CODE = 7;
    private static String IN_APP_UPDATE_TYPE = "FLEXIBLE";
    private static Integer DAYS_FOR_FLEXIBLE_UPDATE = 0;
    private static Integer DAYS_FOR_IMMEDIATE_UPDATE = 0;
    private static final Integer HIGH_PRIORITY_UPDATE = 3;
    private static final Integer MEDIUM_PRIORITY_UPDATE = 1;
    private static AppUpdateManager appUpdateManager;
    private static InstallStateUpdatedListener listener;
    private FrameLayout layout;

    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        layout = (FrameLayout) webView.getView().getParent();
        
        // Initialize AppUpdateManager once
        if (appUpdateManager == null) {
            Context context = cordova.getActivity().getApplicationContext();
            appUpdateManager = AppUpdateManagerFactory.create(context);
        }
    }

    public void onStateUpdate(InstallState state) {
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            popupSnackbarForCompleteUpdate();
        }
    }

    public void checkForUpdate(int updateType, AppUpdateInfo appUpdateInfo) {
        if (updateType == AppUpdateType.FLEXIBLE) {
            IN_APP_UPDATE_TYPE = "FLEXIBLE";
            listener = this::onStateUpdate;
            appUpdateManager.registerListener(listener);
        } else {
            IN_APP_UPDATE_TYPE = "IMMEDIATE";
        }
        
        try {
            appUpdateManager.startUpdateFlowForResult(
                appUpdateInfo, 
                updateType, 
                cordova.getActivity(),
                REQUEST_CODE
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void popupSnackbarForCompleteUpdate() {
        cordova.getActivity().runOnUiThread(() -> {
            Snackbar snackbar = Snackbar.make(layout, "An update has just been downloaded.", 
                Snackbar.LENGTH_INDEFINITE);
            snackbar.setAction("RESTART", view -> appUpdateManager.completeUpdate());
            snackbar.setActionTextColor(
                ContextCompat.getColor(cordova.getContext(), android.R.color.holo_green_light)
            );
            snackbar.show();
        });
    }

    private JSONObject getAndroidArgs(JSONArray args) throws JSONException {
        JSONObject argument = args.getJSONObject(0);
        return argument.getJSONObject("ANDROID");
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        if (!action.equals("update") && !action.equals("getAvailableVersion")) {
            callbackContext.error("Invalid action: " + action);
            return false;
        }

        final Activity activity = cordova.getActivity();
        activity.runOnUiThread(() -> {
            try {
                if (action.equals("getAvailableVersion")) {
                    handleGetAvailableVersion(callbackContext);
                } else {
                    handleUpdateAction(args, callbackContext);
                }
            } catch (JSONException e) {
                callbackContext.error("JSON error: " + e.getMessage());
            }
        });
        
        return true;
    }

    private void handleGetAvailableVersion(CallbackContext callbackContext) {
        appUpdateManager.getAppUpdateInfo()
            .addOnSuccessListener(appUpdateInfo -> {
                int versionCode = appUpdateInfo.availableVersionCode();
                callbackContext.success(versionCode);
            })
            .addOnFailureListener(e -> {
                callbackContext.error("Version check failed: " + e.getMessage());
            });
    }

    private void handleUpdateAction(JSONArray args, CallbackContext callbackContext) 
        throws JSONException {
        JSONObject androidArgs = getAndroidArgs(args);
        String type = androidArgs.getString("type");
        
        if (type.equals("MIXED")) {
            DAYS_FOR_FLEXIBLE_UPDATE = androidArgs.getInt("flexibleUpdateStalenessDays");
            DAYS_FOR_IMMEDIATE_UPDATE = androidArgs.getInt("immediateUpdateStalenessDays");
        } else if (type.equals("FLEXIBLE")) {
            DAYS_FOR_FLEXIBLE_UPDATE = androidArgs.getInt("stallDays");
            DAYS_FOR_IMMEDIATE_UPDATE = Integer.MAX_VALUE;
        } else if (type.equals("IMMEDIATE")) {
            DAYS_FOR_FLEXIBLE_UPDATE = Integer.MAX_VALUE;
            DAYS_FOR_IMMEDIATE_UPDATE = androidArgs.getInt("stallDays");
        }

        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(appUpdateInfo -> {
            int clientStaleness = appUpdateInfo.clientVersionStalenessDays() != null ? 
                appUpdateInfo.clientVersionStalenessDays() : 0;

            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
                if (appUpdateInfo.updatePriority() >= HIGH_PRIORITY_UPDATE && 
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    checkForUpdate(AppUpdateType.IMMEDIATE, appUpdateInfo);
                } else if (appUpdateInfo.updatePriority() >= MEDIUM_PRIORITY_UPDATE && 
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                    checkForUpdate(AppUpdateType.FLEXIBLE, appUpdateInfo);
                } else if (clientStaleness >= DAYS_FOR_IMMEDIATE_UPDATE && 
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                    checkForUpdate(AppUpdateType.IMMEDIATE, appUpdateInfo);
                } else if (clientStaleness >= DAYS_FOR_FLEXIBLE_UPDATE && 
                    appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                    checkForUpdate(AppUpdateType.FLEXIBLE, appUpdateInfo);
                }
            }
            
            callbackContext.success(appUpdateInfo.availableVersionCode());
        }).addOnFailureListener(e -> {
            callbackContext.error("Update check failed: " + e.getMessage());
        });
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        appUpdateManager.getAppUpdateInfo().addOnSuccessListener(appUpdateInfo -> {
            if (IN_APP_UPDATE_TYPE.equals("FLEXIBLE") && 
                appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                popupSnackbarForCompleteUpdate();
            }

            if (appUpdateInfo.updateAvailability() == 
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                try {
                    checkForUpdate(AppUpdateType.IMMEDIATE, appUpdateInfo);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        if (appUpdateManager != null && listener != null) {
            appUpdateManager.unregisterListener(listener);
        }
        super.onDestroy();
    }
}
