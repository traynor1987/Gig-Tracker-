package site.chatgpt.traynor1987.gigtracker;

final class NativeLocationBus {
    interface Listener {
        void onPendingSamples();
    }

    private static volatile Listener listener;

    private NativeLocationBus() {}

    static void setListener(Listener value) {
        listener = value;
    }

    static void notifyPendingSamples() {
        Listener current = listener;
        if (current != null) current.onPendingSamples();
    }
}
