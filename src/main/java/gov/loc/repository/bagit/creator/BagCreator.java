package gov.loc.repository.bagit.creator;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.loc.repository.bagit.annotation.Incubating;
import gov.loc.repository.bagit.domain.Bag;
import gov.loc.repository.bagit.domain.Manifest;
import gov.loc.repository.bagit.domain.Version;
import gov.loc.repository.bagit.hash.Hasher;
import gov.loc.repository.bagit.hash.SupportedAlgorithm;
import gov.loc.repository.bagit.writer.BagWriter;

/**
 * Responsible for creating a bag in place.
 */
public final class BagCreator {
  private static final Logger logger = LoggerFactory.getLogger(BagCreator.class);
  
  private BagCreator(){}
  
  /**
   * Creates a basic(only required elements) bag in place for version 0.97.
   * This method moves and creates files, thus if an error is thrown during operation it may leave the filesystem 
   * in an unknown state of transition. Thus this is <b>not thread safe</b>
   * 
   * @param root the directory that will become the base of the bag and where to start searching for content
   * @param algorithms an collection of {@link SupportedAlgorithm} implementations
   * @param includeHidden to include hidden files when generating the bagit files, like the manifests
   * @throws NoSuchAlgorithmException if {@link MessageDigest} can't find the algorithm
   * @throws IOException if there is a problem writing or moving file(s)
   * @return a {@link Bag} object representing the newly created bagit bag
   */
  public static Bag bagInPlace(final Path root, final Collection<SupportedAlgorithm> algorithms, final boolean includeHidden) throws NoSuchAlgorithmException, IOException{
    final Bag bag = new Bag(new Version(0, 97));
    bag.setRootDir(root);
    logger.info("Creating a bag with version: [{}] in directory: [{}]", bag.getVersion(), root);
    
    final Path dataDir = root.resolve("data");
    Files.createDirectory(dataDir);
    final DirectoryStream<Path> directoryStream = Files.newDirectoryStream(root);
    for(final Path path : directoryStream){
      if(!path.equals(dataDir) && !Files.isHidden(path) || includeHidden){
        Files.move(path, dataDir.resolve(path.getFileName()));
      }
    }
    
    logger.info("Creating payload manifest(s)");
    final Map<Manifest, MessageDigest> map = Hasher.createManifestToMessageDigestMap(algorithms);
    final AddPayloadToBagManifestVistor visitor = new AddPayloadToBagManifestVistor(map, includeHidden);
    Files.walkFileTree(dataDir, visitor);
    
    bag.getPayLoadManifests().addAll(map.keySet());
    BagWriter.writeBagitFile(bag.getVersion(), bag.getFileEncoding(), root);
    BagWriter.writePayloadManifests(bag.getPayLoadManifests(), root, root, bag.getFileEncoding());
    
    
    return bag;
  }
  
  /**
   * Creates a basic(only required elements) .bagit bag in place.
   * This creates files and directories, thus if an error is thrown during operation it may leave the filesystem 
   * in an unknown state of transition. Thus this is <b>not thread safe</b>
   * 
   * @param root the directory that will become the base of the bag and where to start searching for content
   * @param algorithms an collection of {@link SupportedAlgorithm} implementations
   * @param includeHidden to include hidden files when generating the bagit files, like the manifests
   * @return a {@link Bag} object representing the newly created bagit bag
   * @throws NoSuchAlgorithmException if {@link MessageDigest} can't find the algorithm
   * @throws IOException if there is a problem writing files or .bagit directory
   */
  @Incubating
  public static Bag createDotBagit(final Path root, final Collection<SupportedAlgorithm> algorithms, final boolean includeHidden) throws NoSuchAlgorithmException, IOException{
    final Bag bag = new Bag(new Version(0, 98));
    bag.setRootDir(root);
    logger.info("Creating a bag with version: [{}] in directory: [{}]", bag.getVersion(), root);
    
    final Path dotbagitDir = root.resolve(".bagit");
    Files.createDirectories(dotbagitDir);
    
    logger.info("Creating payload manifest");
    final Map<Manifest, MessageDigest> map = Hasher.createManifestToMessageDigestMap(algorithms);
    final AddPayloadToBagManifestVistor visitor = new AddPayloadToBagManifestVistor(map, includeHidden);
    Files.walkFileTree(root, visitor);
    
    bag.getPayLoadManifests().addAll(map.keySet());
    BagWriter.writeBagitFile(bag.getVersion(), bag.getFileEncoding(), dotbagitDir);
    BagWriter.writePayloadManifests(bag.getPayLoadManifests(), dotbagitDir, root, bag.getFileEncoding());
    
    return bag;
  }
}