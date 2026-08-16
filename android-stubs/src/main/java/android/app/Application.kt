package android.app

import android.content.Context

/** Minimal desktop stub of android.app.Application used by some plugins. */
class Application : Context() {
    interface ActivityLifecycleCallbacks
    interface OnProvideAssistContentListener
}