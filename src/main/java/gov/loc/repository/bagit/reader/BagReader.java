package gov.loc.repository.bagit.reader;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.loc.repository.bagit.domain.Bag;
import gov.loc.repository.bagit.domain.FetchItem;
import gov.loc.repository.bagit.domain.Manifest;
import gov.loc.repository.bagit.domain.Version;
import gov.loc.repository.bagit.exceptions.InvalidBagMetadataException;
import gov.loc.repository.bagit.exceptions.InvalidBagitFileFormatException;
import gov.loc.repository.bagit.exceptions.MaliciousPathException;
import gov.loc.repository.bagit.exceptions.UnparsableVersionException;
import gov.loc.repository.bagit.exceptions.UnsupportedAlgorithmException;
import gov.loc.repository.bagit.hash.BagitAlgorithmNameToSupportedAlgorithmMapping;
import gov.loc.repository.bagit.hash.StandardBagitAlgorithmNameToSupportedAlgorithmMapping;
import gov.loc.repository.bagit.hash.SupportedAlgorithm;
import gov.loc.repository.bagit.util.PathUtils;

/**
 * Responsible for reading a bag from the filesystem.
 */
@SuppressWarnings("PMD.TooManyMethods")
public final class BagReader {
  private static final Logger logger = LoggerFactory.getLogger(BagReader.class);
  private static final String ERROR_PREFIX = "Path [";
  
  private final BagitAlgorithmNameToSupportedAlgorithmMapping nameMapping;
  
  public BagReader(){
    this.nameMapping = new StandardBagitAlgorithmNameToSupportedAlgorithmMapping();
  }
  
  public BagReader(final BagitAlgorithmNameToSupportedAlgorithmMapping nameMapping){
    this.nameMapping = nameMapping;
  }
  
  /**
   * Read the bag from the filesystem and create a bag object
   * 
   * @param rootDir the root directory of the bag 
   * @throws IOException if there is a problem reading a file
   * @return a {@link Bag} object representing a bag on the filesystem
   * 
   * @throws UnparsableVersionException If there is a problem parsing the bagit version
   * @throws MaliciousPathException if there is path that is referenced in the manifest or fetch file that is outside the bag root directory
   * @throws InvalidBagMetadataException if the metadata or bagit.txt file does not conform to the bagit spec
   * @throws UnsupportedAlgorithmException if the manifest uses a algorithm that isn't supported
   * @throws InvalidBagitFileFormatException if the manifest or fetch file is not formatted properly
   */
  public Bag read(final Path rootDir) throws IOException, UnparsableVersionException, MaliciousPathException, InvalidBagMetadataException, UnsupportedAlgorithmException, InvalidBagitFileFormatException{
    final Bag bag = new Bag();
    
    //@Incubating
    Path bagitDir = rootDir.resolve(".bagit");
    if(!Files.exists(bagitDir)){
      bagitDir = rootDir;
    }
    bag.setRootDir(rootDir);
    
    final Path bagitFile = bagitDir.resolve("bagit.txt");
    final SimpleImmutableEntry<Version, Charset> bagitInfo = readBagitTextFile(bagitFile);
    bag.setVersion(bagitInfo.getKey());
    bag.setFileEncoding(bagitInfo.getValue());
    
    readAllManifests(bagitDir, bag);
    
    bag.getMetadata().addAll(readBagMetadata(bagitDir, bag.getFileEncoding()));
    
    final Path fetchFile = bagitDir.resolve("fetch.txt");
    if(Files.exists(fetchFile)){
      bag.getItemsToFetch().addAll(readFetch(fetchFile, bag.getFileEncoding(), bag.getRootDir()));
    }
    
    return bag;
  }
  
  /**
   * Read the bagit.txt file and return the version and encoding.
   * 
   * @param bagitFile the bagit.txt file
   * @return the bag {@link Version} and {@link Charset} encoding of the tag files
   * 
   * @throws IOException if there is a problem reading a file
   * @throws UnparsableVersionException if there is a problem parsing the bagit version number
   * @throws InvalidBagMetadataException if the bagit.txt file does not conform to the bagit spec
   */
  public SimpleImmutableEntry<Version, Charset> readBagitTextFile(final Path bagitFile) throws IOException, UnparsableVersionException, InvalidBagMetadataException{
    logger.debug("Reading bagit.txt file");
    final List<SimpleImmutableEntry<String, String>> pairs = readKeyValuesFromFile(bagitFile, ":", StandardCharsets.UTF_8);
    
    String version = "";
    Charset encoding = StandardCharsets.UTF_8;
    for(final SimpleImmutableEntry<String, String> pair : pairs){
      if("BagIt-Version".equals(pair.getKey())){
        version = pair.getValue();
        logger.debug("BagIt-Version is [{}]", version);
      }
      if("Tag-File-Character-Encoding".equals(pair.getKey())){
        encoding = Charset.forName(pair.getValue());
        logger.debug("Tag-File-Character-Encoding is [{}]", encoding);
      }
    }
    
    return new SimpleImmutableEntry<Version, Charset>(parseVersion(version), encoding);
  }
  
  /*
   * parses the version string into a {@link Version} object
   */
  Version parseVersion(final String version) throws UnparsableVersionException{
    if(!version.contains(".")){
      throw new UnparsableVersionException("Version must be in format MAJOR.MINOR but was " + version);
    }
    
    final String[] parts = version.split("\\.");
    final int major = Integer.parseInt(parts[0]);
    final int minor = Integer.parseInt(parts[1]);
    
    return new Version(major, minor);
  }
  
  /*
   * Finds and reads all manifest files in the rootDir and adds them to the given bag.
   */
  void readAllManifests(final Path rootDir, final Bag bag) throws IOException, MaliciousPathException, UnsupportedAlgorithmException, InvalidBagitFileFormatException{
    logger.info("Attempting to find and read manifests");
    final DirectoryStream<Path> manifests = getAllManifestFiles(rootDir);
    
    for (final Path path : manifests){
      final String filename = PathUtils.getFilename(path);
      
      if(filename.startsWith("tagmanifest-")){
        logger.debug("Found tag manifest [{}]", path);
        bag.getTagManifests().add(readManifest(path, bag.getRootDir(), bag.getFileEncoding()));
      }
      else if(filename.startsWith("manifest-")){
        logger.debug("Found payload manifest [{}]", path);
        bag.getPayLoadManifests().add(readManifest(path, bag.getRootDir(), bag.getFileEncoding()));
      }
    }
  }
  
  /*
   * Get a list of all the tag and payload manifests
   */
  private DirectoryStream<Path> getAllManifestFiles(final Path rootDir) throws IOException{
    final DirectoryStream.Filter<Path> filter = new DirectoryStream.Filter<Path>() {
      public boolean accept(final Path file) throws IOException {
        if(file == null || file.getFileName() == null){ return false;}
        final String filename = PathUtils.getFilename(file);
        return filename.startsWith("tagmanifest-") || filename.startsWith("manifest-");
      }
    };
    
    return Files.newDirectoryStream(rootDir, filter);
  }
  
  /**
   * Reads a manifest file and converts it to a {@link Manifest} object.
   * 
   * @param manifestFile a specific manifest file
   * @param bagRootDir the root directory of the bag
   * @param charset the encoding to use when reading the manifest file
   * @return the converted manifest object from the file
   * 
   * @throws IOException if there is a problem reading a file
   * @throws MaliciousPathException if there is path that is referenced in the manifest that is outside the bag root directory
   * @throws UnsupportedAlgorithmException if the manifest uses a algorithm that isn't supported
   * @throws InvalidBagitFileFormatException if the manifest is not formatted properly
   */
  public Manifest readManifest(final Path manifestFile, final Path bagRootDir, final Charset charset) throws IOException, MaliciousPathException, UnsupportedAlgorithmException, InvalidBagitFileFormatException{
    logger.debug("Reading manifest [{}]", manifestFile);
    final String alg = PathUtils.getFilename(manifestFile).split("[-\\.]")[1];
    final SupportedAlgorithm algorithm = nameMapping.getSupportedAlgorithm(alg);
    
    final Manifest manifest = new Manifest(algorithm);
    
    final Map<Path, String> filetToChecksumMap = readChecksumFileMap(manifestFile, bagRootDir, charset);
    manifest.setFileToChecksumMap(filetToChecksumMap);
    
    return manifest;
  }
  
  /*
   * read the manifest file into a map of files and checksums
   */
  Map<Path, String> readChecksumFileMap(final Path manifestFile, final Path bagRootDir, final Charset charset) throws IOException, MaliciousPathException, InvalidBagitFileFormatException{
    final HashMap<Path, String> map = new HashMap<>();
    final BufferedReader br = Files.newBufferedReader(manifestFile, charset);

    String line = br.readLine();
    while(line != null){
      final String[] parts = line.split("\\s+", 2);
      final Path file = createFileFromManifest(bagRootDir, parts[1]);
      logger.debug("Read checksum [{}] and file [{}] from manifest [{}]", parts[0], file, manifestFile);
      map.put(file, parts[0]);
      line = br.readLine();
    }
    
    br.close();
    
    return map;
  }
  
  /*
   * Create the file and check it for various things, like starting with a *
   */
  private Path createFileFromManifest(final Path bagRootDir, final String path) throws MaliciousPathException, InvalidBagitFileFormatException{
    String fixedPath = path;
    if(path.charAt(0) == '*'){
      logger.warn("Encountered path that was created by non-bagit tool. Removing * from path. Please remove all * from manifest files!");
      fixedPath = path.substring(1); //remove the * from the path
    }
    
    if(path.contains("\\")){
      throw new InvalidBagitFileFormatException(ERROR_PREFIX + path + "] is invalid due to the use of the path separactor [\\]");
    }
    
    if(path.contains("~/")){
      throw new MaliciousPathException(ERROR_PREFIX + path + "] is trying to be malicious and access a file outside the bag");
    }

    fixedPath = PathUtils.decodeFilname(fixedPath);
    Path file = bagRootDir.resolve(fixedPath).normalize();
    if(fixedPath.startsWith("file://")){
      try {
        file = Paths.get(new URI(fixedPath));
      } catch (URISyntaxException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
    
    if(!file.normalize().startsWith(bagRootDir)){
      throw new MaliciousPathException(ERROR_PREFIX + file + "] is outside the bag root directory of " + bagRootDir + 
          "! This is not allowed according to the bagit specification!");
    }
    
    return file;
  }
  
  /**
   * Reads the bag metadata file (bag-info.txt or package-info.txt) and returns it.
   * 
   * @param rootDir the root directory of the bag
   * @param encoding the encoding of the bagit.txt file
   * @return the bag-info.txt (metadata) information
   * 
   * @throws IOException if there is a problem reading a file
   * @throws InvalidBagMetadataException if the metadata file does not conform to the bagit spec
   */
  public List<SimpleImmutableEntry<String, String>> readBagMetadata(final Path rootDir, final Charset encoding) throws IOException, InvalidBagMetadataException{
    logger.info("Attempting to read bag metadata file");
    List<SimpleImmutableEntry<String, String>> metadata = new ArrayList<>();
    
    final Path bagInfoFile = rootDir.resolve("bag-info.txt");
    if(Files.exists(bagInfoFile)){
      logger.debug("Found [{}] file", bagInfoFile);
      metadata = readKeyValuesFromFile(bagInfoFile, ":", encoding);
    }
    final Path packageInfoFile = rootDir.resolve("package-info.txt"); //only exists in versions 0.93 - 0.95
    if(Files.exists(packageInfoFile)){
      logger.debug("Found [{}] file", packageInfoFile);
      metadata = readKeyValuesFromFile(packageInfoFile, ":", encoding);
    }
    
    return metadata;
  }
  
  /**
   * Reads a fetch.txt file
   * 
   * @param fetchFile the specific fetch file
   * @param encoding the encoding to read the file with
   * @param bagRootDir the root directory of the bag
   * @return a list of items to fetch
   * 
   * @throws IOException if there is a problem reading a file
   * @throws MaliciousPathException if the path was crafted to point outside the bag directory
   * @throws InvalidBagitFileFormatException if the fetch format does not follow the bagit specification
   */
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  public List<FetchItem> readFetch(final Path fetchFile, final Charset encoding, final Path bagRootDir) throws IOException, MaliciousPathException, InvalidBagitFileFormatException{
    logger.info("Attempting to read [{}]", fetchFile);
    final BufferedReader br = Files.newBufferedReader(fetchFile, encoding);
    final List<FetchItem> itemsToFetch = new ArrayList<>();

    String line = br.readLine();
    String[] parts = null;
    long length = 0;
    URL url = null;
    while(line != null){
      parts = line.split("\\s+", 3);
      final Path path = createFileFromManifest(bagRootDir, parts[2]);
      length = parts[1].equals("-") ? -1 : Long.decode(parts[1]);
      url = new URL(parts[0]);
      
      logger.debug("Read URL [{}] length [{}] path [{}] from fetch file [{}]", url, length, parts[2], fetchFile);
      final FetchItem itemToFetch = new FetchItem(url, length, path);
      itemsToFetch.add(itemToFetch);
      
      line = br.readLine();
    }

    return itemsToFetch;
  }
  
  /*
   * Generic method to read key value pairs from the bagit files, like bagit.txt or bag-info.txt
   */
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  List<SimpleImmutableEntry<String, String>> readKeyValuesFromFile(final Path file, final String splitRegex, final Charset charset) throws IOException, InvalidBagMetadataException{
    final List<SimpleImmutableEntry<String, String>> keyValues = new ArrayList<>();
    final BufferedReader br = Files.newBufferedReader(file, charset);

    String line = br.readLine();
    while(line != null){
      if(line.matches("^\\s+.*")){
        final SimpleImmutableEntry<String, String> oldKeyValue = keyValues.remove(keyValues.size() -1);
        final SimpleImmutableEntry<String, String> newKeyValue = new SimpleImmutableEntry<String, String>(oldKeyValue.getKey(), oldKeyValue.getValue() + System.lineSeparator() +line);
        keyValues.add(newKeyValue);
        
        logger.debug("Found an indented line - merging it with key [{}]", oldKeyValue.getKey());
      }
      else{
        final String[] parts = line.split(splitRegex, 2);
        if(parts.length != 2){
          final StringBuilder message = new StringBuilder(300);
          message.append("Line [").append(line)
            .append("] does not meet the bagit specification for a bag tag file. Perhaps you meant to indent it " +
            "by a space or a tab? Or perhaps you didn't use a colon to separate the key from the value?" +
            "It must follow the form of <key>:<value> or if continuing from another line must be indented " +
            "by a space or a tab.");
          
          throw new InvalidBagMetadataException(message.toString());
        }
        final String key = parts[0].trim();
        final String value = parts[1].trim();
        logger.debug("Found key [{}] value [{}] in file [{}] using regex [{}]", key, value, file, splitRegex);
        keyValues.add(new SimpleImmutableEntry<String, String>(key, value));
      }
       
      line = br.readLine();
    }
    
    return keyValues;
  }

  public BagitAlgorithmNameToSupportedAlgorithmMapping getNameMapping() {
    return nameMapping;
  }
}
