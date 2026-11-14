package backend;

/** A request that cannot be done. The message is shown to the user; status is the HTTP status code. */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static ApiException badRequest(String msg)   { return new ApiException(400, msg); }   // invalid input
    public static ApiException unauthorized(String msg) { return new ApiException(401, msg); }   // not logged in
    public static ApiException forbidden(String msg)    { return new ApiException(403, msg); }   // not allowed (not admin)
    public static ApiException conflict(String msg)     { return new ApiException(409, msg); }   // not possible right now
}
