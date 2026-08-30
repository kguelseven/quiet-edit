package org.korhan.quietedit.ingest;

/**
 * A run was refused because one is already in flight.
 *
 * <p>Deliberately a plain domain exception with no HTTP in it: the service does not
 * know it has a REST caller, and the scheduler is not one.
 * {@link IngestExceptionHandler} is what turns this into a status code.
 */
public class IngestAlreadyRunningException extends RuntimeException {

    public IngestAlreadyRunningException() {
        super("An ingest run is already in progress.");
    }
}
