// Supplies a real WebView error callback value through the package-private platform constructor.
package android.webkit;

public final class CodedWebResourceError extends WebResourceError {
    private final int errorCode;

    public CodedWebResourceError(int errorCode) {
        this.errorCode = errorCode;
    }

    @Override
    public int getErrorCode() {
        return errorCode;
    }

    @Override
    public CharSequence getDescription() {
        return "Error " + errorCode;
    }
}
