package gov.loc.repository.bagit.exceptions;

/**
 * If the version string in the bagit.txt file was not in the form &lt;MAJOR&gt;.&lt;MINOR&gt; 
 */
public class UnparsableVersionException extends Exception {
  private static final long serialVersionUID = 1L;

  public UnparsableVersionException(final String message){
    super(message);
  }
}
