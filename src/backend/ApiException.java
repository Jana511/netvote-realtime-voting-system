package backend;

/** A request that cannot be done. The message is shown to the user; status is the HTTP status code. */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int status;

    ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    static ApiException badRequest(String msg)   { return new ApiException(400, msg); }   // invalid input
    static ApiException unauthorized(String msg) { return new ApiException(401, msg); }   // not logged in
    static ApiException forbidden(String msg)    { return new ApiException(403, msg); }   // not allowed (not admin)
    static ApiException conflict(String msg)     { return new ApiException(409, msg); }   // not possible right now
}
