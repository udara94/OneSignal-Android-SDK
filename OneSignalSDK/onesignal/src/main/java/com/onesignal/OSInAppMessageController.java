package com.onesignal;

import android.support.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.onesignal.OSDynamicTriggerController.OSDynamicTriggerControllerObserver;
import com.onesignal.OneSignalRestClient.ResponseHandler;

class OSInAppMessageController implements OSDynamicTriggerControllerObserver {
    private static ArrayList<String> PREFERRED_VARIANT_ORDER = new ArrayList<String>() {{
        add("android"); add("app"); add("all");
    }};

    private static OSInAppMessageController sharedInstance;

    OSTriggerController triggerController;
    private ArrayList<OSInAppMessage> messages;
    final ArrayList<OSInAppMessage> messageDisplayQueue;

    boolean inAppMessagingEnabled;

    static OSInAppMessageController getController() {
        if (sharedInstance == null)
            sharedInstance = new OSInAppMessageController();

        return sharedInstance;
    }

    private OSInAppMessageController() {
        messages = new ArrayList<>();
        messageDisplayQueue = new ArrayList<>();
        triggerController = new OSTriggerController(this);
        inAppMessagingEnabled = OneSignalPrefs.getBool(OneSignalPrefs.PREFS_ONESIGNAL, OneSignalPrefs.PREFS_ONESIGNAL_MESSAGING_ENABLED, true);
    }

    // Called after the device is registered from UserStateSynchronizer
    //    which is the REST call to create the player record on_session
    void receivedInAppMessageJson(JSONArray json) throws JSONException {
        ArrayList<OSInAppMessage> newMessages = new ArrayList<>();

        for (int i = 0; i < json.length(); i++) {
            JSONObject messageJson = json.getJSONObject(i);
            OSInAppMessage message = new OSInAppMessage(messageJson);
            newMessages.add(message);
        }

        messages = newMessages;

        evaluateInAppMessages();
    }

    private void evaluateInAppMessages() {
        for (OSInAppMessage message : messages) {
            if (triggerController.evaluateMessageTriggers(message))
                messageCanBeDisplayed(message);
        }
    }

    private static String variantIdForMessage(OSInAppMessage message) {
        String languageIdentifier = OSUtils.getCorrectedLanguage();

        for (String variant : PREFERRED_VARIANT_ORDER) {
            if (message.variants.containsKey(variant)) {
                HashMap<String, String> variantMap = message.variants.get(variant);

                if (variantMap.containsKey(languageIdentifier))
                    return variantMap.get(languageIdentifier);
                else if (variantMap.containsKey("default"))
                    return variantMap.get("default");
            }
        }

        return null;
    }

    private String htmlPathForMessage(OSInAppMessage message) {
        String variantId = variantIdForMessage(message);

        if (variantId == null) {
            OneSignal.onesignalLog(OneSignal.LOG_LEVEL.ERROR, "Unable to find a variant for in-app message " + message.messageId);
            return null;
        }

        return "in_app_messages/" + message.messageId + "/variants/" + variantId + "/html";
    }

    private static void printHttpErrorForInAppMessageRequest(String requestType, int statusCode, String response) {
        OneSignal.onesignalLog(OneSignal.LOG_LEVEL.ERROR, "Encountered a " + String.valueOf(statusCode) + " error while attempting in-app message " + requestType + " request: " + response);
    }

    private static void onMessageWasShown(OSInAppMessage message) {
        final String variantId = variantIdForMessage(message);
        if (variantId == null)
            return;

        try {
            JSONObject json = new JSONObject() {{
                put("app_id", OneSignal.appId);
                put("player_id", OneSignal.getUserId());
                put("variant_id", variantId);
            }};

            OneSignalRestClient.post("in_app_messages/impression/" + message.messageId, json, new ResponseHandler() {
                @Override
                void onFailure(int statusCode, String response, Throwable throwable) {
                    printHttpErrorForInAppMessageRequest("impression", statusCode, response);
                }
            });
        } catch (JSONException e) {
            e.printStackTrace();
            OneSignal.onesignalLog(OneSignal.LOG_LEVEL.ERROR, "Unable to execute in-app message impression HTTP request due to invalid JSON");
        }
    }

    // TODO: When a message action occurs, call this method
    private void onMessageActionOccurredOnMessage(final OSInAppMessage message, final OSInAppMessageAction action) {
        final String variantId = variantIdForMessage(message);
        if (variantId == null)
            return;

        final OSInAppMessageAction action = new OSInAppMessageAction(actionJson);

        if (action.actionUrl != null) {
            if (action.urlTarget == OSInAppMessageAction.OSInAppMessageActionUrlType.BROWSER)
                OSUtils.openURLInBrowser(action.actionUrl);
            else if (action.urlTarget == OSInAppMessageAction.OSInAppMessageActionUrlType.IN_APP_WEBVIEW)
                OneSignalChromeTab.open(action.actionUrl, true);
        }
        try {
            JSONObject json = new JSONObject() {{
                put("app_id", OneSignal.appId);
                put("player_id", OneSignal.getUserId());
                put("action_id", action.actionId);
                put("variant_id", variantId);
            }};

            OneSignalRestClient.post("in_app_messages/" + message.messageId + "/engagement", json, new ResponseHandler() {
                @Override
                void onFailure(int statusCode, String response, Throwable throwable) {
                    printHttpErrorForInAppMessageRequest("engagement", statusCode, response);
                }
            });
        } catch (JSONException e) {
            e.printStackTrace();
            OneSignal.onesignalLog(OneSignal.LOG_LEVEL.ERROR, "Unable to execute in-app message action HTTP request due to invalid JSON");
        }
    }

    private void messageCanBeDisplayed(OSInAppMessage message) {
        if (!inAppMessagingEnabled)
            return;

        synchronized (messageDisplayQueue) {
            messageDisplayQueue.add(message);

            if (messageDisplayQueue.size() > 1) {
                // means we are already displaying a message
                // this message will be displayed afterwards
                return;
            }

            displayMessage(message);
        }
    }

    @Nullable
    OSInAppMessage getCurrentDisplayedInAppMessage() {
        return messageDisplayQueue.size() > 0 ? messageDisplayQueue.get(0) : null;
    }

    // TODO: Call this method when the UI for an in-app message is dismissed, ie.
    // when the user taps CLOSE or the time expires
    // NOTE: Do NOT call this method until any pending animation (ie. a dismiss
    // animation) is completely finished
    void messageWasDismissed(OSInAppMessage message) {
        synchronized (messageDisplayQueue) {
            if (!messageDisplayQueue.remove(message)) { //something really bad happened.
                OneSignal.onesignalLog(OneSignal.LOG_LEVEL.ERROR, "An in-app message was removed from the display queue before it was finished displaying.");
                return;
            }

            if (messageDisplayQueue.size() > 0) {
                //display the next message in the queue
                displayMessage(messageDisplayQueue.get(0));
            }
        }
    }

    private void displayMessage(OSInAppMessage message) {
        onMessageWasShown(message);

        // TODO: UI presentation logic
        String htmlPath = htmlPathForMessage(message);
    }

    @Override
    public void messageTriggerConditionChanged() {
        // This method is called when a time-based trigger timer fires, meaning the message can
        // probably be shown now. So the current message conditions should be re-evaluated
        evaluateInAppMessages();
    }

    /**
     * Trigger logic
     *
     * These methods mostly pass data to the Trigger Controller, but also cause the SDK to
     * re-evaluate messages to see if we should display a message now that the trigger
     * conditions have changed.
     */
    void addTriggers(Map<String, Object> newTriggers) {
        triggerController.addTriggers(newTriggers);
        evaluateInAppMessages();
    }

    void removeTriggersForKeys(Collection<String> keys) {
        triggerController.removeTriggersForKeys(keys);
        evaluateInAppMessages();
    }

    void setInAppMessagingEnabled(boolean enabled) {
        inAppMessagingEnabled = enabled;
        OneSignalPrefs.saveBool(OneSignalPrefs.PREFS_ONESIGNAL, OneSignalPrefs.PREFS_ONESIGNAL_MESSAGING_ENABLED, enabled);
    }

    @Nullable
    Object getTriggerValue(String key) {
        return triggerController.getTriggerValue(key);
    }
}
