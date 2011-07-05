package experimentalcode.marisa.index.xtree;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import de.lmu.ifi.dbs.elki.data.HyperBoundingBox;
import de.lmu.ifi.dbs.elki.data.ModifiableHyperBoundingBox;
import de.lmu.ifi.dbs.elki.data.NumberVector;
import de.lmu.ifi.dbs.elki.data.spatial.SpatialComparable;
import de.lmu.ifi.dbs.elki.data.spatial.SpatialUtil;
import de.lmu.ifi.dbs.elki.database.ids.DBIDUtil;
import de.lmu.ifi.dbs.elki.distance.distancefunction.SquaredEuclideanDistanceFunction;
import de.lmu.ifi.dbs.elki.distance.distancevalue.DoubleDistance;
import de.lmu.ifi.dbs.elki.index.tree.BreadthFirstEnumeration;
import de.lmu.ifi.dbs.elki.index.tree.DistanceEntry;
import de.lmu.ifi.dbs.elki.index.tree.IndexTreePath;
import de.lmu.ifi.dbs.elki.index.tree.TreeIndexHeader;
import de.lmu.ifi.dbs.elki.index.tree.TreeIndexPathComponent;
import de.lmu.ifi.dbs.elki.index.tree.spatial.BulkSplit.Strategy;
import de.lmu.ifi.dbs.elki.index.tree.spatial.SpatialEntry;
import de.lmu.ifi.dbs.elki.index.tree.spatial.SpatialPointLeafEntry;
import de.lmu.ifi.dbs.elki.index.tree.spatial.rstarvariants.AbstractRStarTree;
import de.lmu.ifi.dbs.elki.index.tree.spatial.rstarvariants.NonFlatRStarTree;
import de.lmu.ifi.dbs.elki.persistent.PageFile;
import de.lmu.ifi.dbs.elki.persistent.PageFileUtil;
import de.lmu.ifi.dbs.elki.persistent.PersistentPageFile;
import de.lmu.ifi.dbs.elki.utilities.exceptions.AbortException;
import experimentalcode.marisa.index.xtree.util.SplitHistory;
import experimentalcode.marisa.index.xtree.util.XSplitter;

/**
 * Base class for XTree implementations and other extensions; derived from
 * {@link NonFlatRStarTree}.
 * 
 * @author Marisa Thoma
 * 
 * @param <O> Object type
 * @param <N> Node type
 * @param <E> Entry type
 */
public abstract class XTreeBase<N extends XNode<E, N>, E extends SpatialEntry> extends AbstractRStarTree<N, E> {
  /**
   * If <code>true</code>, the expensive call of
   * {@link #calculateOverlapIncrease(List, SpatialEntry, HyperBoundingBox)} is
   * omitted for supernodes. This may lead to longer query times, however, is
   * necessary for enabling the construction of the tree for some
   * parameterizations.
   * */
  public boolean OMIT_OVERLAP_INCREASE_4_SUPERNODES = true;

  /**
   * Mapping id to supernode. Supernodes are appended to the end of the index
   * file when calling #commit();
   */
  protected Map<Long, N> supernodes = new HashMap<Long, N>();

  /**
   * Relative min entries value.
   */
  private double relativeMinEntries;

  /**
   * Relative minimum fanout.
   */
  private double relativeMinFanout;

  /**
   * Minimum size to be allowed for page sizes after a split in case of a
   * minimum overlap split.
   */
  protected int min_fanout;

  /** Fraction of pages to be re-inserted instead of trying a split. */
  protected float reinsert_fraction = .3f;

  /** Maximum overlap for a split partition. */
  protected float max_overlap = .2f;

  /** Dimensionality of the {@link NumberVector}s stored in this tree. */
  protected int dimensionality;

  /** Number of elements (of type <O>) currently contained in this tree. */
  protected long num_elements = 0;

  /**
   * The maximum overlap is calculated as the ratio of total data objects in the
   * overlapping region.
   */
  public static final int DATA_OVERLAP = 0;

  /**
   * The maximum overlap is calculated as the fraction of the overlapping region
   * of the two original mbrs:
   * <code>(overlap volume of mbr 1 and mbr 2) / (volume of mbr 1 + volume of mbr 2)</code>
   */
  public static final int VOLUME_OVERLAP = 1;

  /**
   * Type of overlap to be used for testing on maximum overlap. Must be one of
   * {@link #DATA_OVERLAP} and {@link #VOLUME_OVERLAP}.
   */
  protected int overlap_type = DATA_OVERLAP;

  public static final int QUEUE_INIT = 50;

  /**
   * Constructor.
   * 
   * @param pagefile the page file
   * @param bulk
   * @param bulkLoadStrategy
   * @param insertionCandidates
   * @param relativeMinEntries
   * @param relativeMinFanout
   * @param reinsert_fraction
   * @param max_overlap
   * @param overlap_type
   */
  public XTreeBase(PageFile<N> pagefile, boolean bulk, Strategy bulkLoadStrategy, int insertionCandidates, double relativeMinEntries, double relativeMinFanout, float reinsert_fraction, float max_overlap, int overlap_type) {
    super(pagefile, bulk, bulkLoadStrategy, insertionCandidates);
    this.relativeMinEntries = relativeMinEntries;
    this.relativeMinFanout = relativeMinFanout;
    this.reinsert_fraction = reinsert_fraction;
    this.max_overlap = max_overlap;
    this.overlap_type = overlap_type;
  }

  /**
   * Returns true if in the specified node an overflow occurred, false
   * otherwise.
   * 
   * @param node the node to be tested for overflow
   * @return true if in the specified node an overflow occurred, false otherwise
   */
  @Override
  protected boolean hasOverflow(N node) {
    if(node.isLeaf()) {
      return node.getNumEntries() == leafCapacity;
    }
    else {
      if(node.isSuperNode()) // supernode capacity differs from normal capacity
        return node.getNumEntries() == node.getCapacity();
      return node.getNumEntries() == dirCapacity;
    }
  }

  /**
   * Returns true if in the specified node an underflow occurred, false
   * otherwise. If <code>node</code> is a supernode, never returns
   * <code>true</code>, as this method automatically shortens the node's
   * capacity by one page size in case of an underflow. If this leads to a
   * normal page size, the node is converted into a normal (non-super) node an
   * it is removed from {@link #supernodes}.
   * 
   * @param node the node to be tested for underflow
   * @return true if in the specified node an underflow occurred, false
   *         otherwise
   */
  @Override
  protected boolean hasUnderflow(N node) {
    if(node.isLeaf()) {
      return node.getNumEntries() < leafMinimum;
    }
    else {
      if(node.isSuperNode()) {
        if(node.getCapacity() - node.getNumEntries() >= dirCapacity) {
          int newCapacity = node.shrinkSuperNode(dirCapacity);
          if(newCapacity == dirCapacity) {
            assert !node.isSuperNode();
            // convert into a normal node (and insert into the index file)
            if(node.isSuperNode()) {
              throw new IllegalStateException("This node should not be a supernode anymore");
            }
            N n = supernodes.remove(new Long(node.getPageID()));
            assert (n != null);
            // update the old reference in the file
            writeNode(node);
          }
        }
        return false;
      }
      return node.getNumEntries() < dirMinimum;
    }
  }

  /**
   * Computes the height of this XTree. Is called by the constructor. and should
   * be overwritten by subclasses if necessary.
   * 
   * @return the height of this XTree
   */
  @Override
  protected int computeHeight() {
    N node = getRoot();
    int tHeight = 1;

    // compute height
    while(!node.isLeaf() && node.getNumEntries() != 0) {
      E entry = node.getEntry(0);
      node = getNode(entry);
      tHeight++;
    }
    return tHeight;
  }

  /**
   * Returns the node with the specified id. Note that supernodes are kept in
   * main memory (in {@link #supernodes}, thus, their listing has to be tested
   * first.
   * 
   * @param nodeID the page id of the node to be returned
   * @return the node with the specified id
   */
  @Override
  public N getNode(Integer nodeID) {
    N nID = supernodes.get(new Long(nodeID));
    if(nID != null) {
      return nID;
    }
    N n = super.getNode(nodeID);
    assert !n.isSuperNode(); // we should have them ALL in #supernodes
    return n;
  }

  @Override
  protected void createEmptyRoot(@SuppressWarnings("unused") E exampleLeaf) {
    N root = createNewLeafNode();
    writeNode(root);
    setHeight(1);
  }

  /**
   * TODO: This does not work at all for supernodes!
   * 
   * Performs a bulk load on this XTree with the specified data. Is called by
   * the constructor and should be overwritten by subclasses if necessary.
   */
  @Override
  protected void bulkLoad(List<E> spatialObjects) {
    throw new UnsupportedOperationException("Bulk Load not supported for XTree");
  }

  /**
   * Get the main memory supernode map for this tree. If it is empty, there are
   * no supernodes in this tree.
   * 
   * @return the supernodes of this tree
   */
  public Map<Long, N> getSupernodes() {
    return supernodes;
  }

  /**
   * Get the overlap type used for this XTree.
   * 
   * @return One of
   *         <dl>
   *         <dt><code>{@link #DATA_OVERLAP}</code></dt>
   *         <dd>The overlap is the ratio of total data objects in the
   *         overlapping region.</dd>
   *         <dt><code>{@link #VOLUME_OVERLAP}</code></dt>
   *         <dd>The overlap is the fraction of the overlapping region of the
   *         two original mbrs:
   *         <code>(overlap volume of mbr 1 and mbr 2) / (volume of mbr 1 + volume of mbr 2)</code>
   *         </dd>
   *         </dl>
   */
  public int get_overlap_type() {
    return overlap_type;
  }

  /** @return the maximally allowed overlap for this XTree. */
  public float get_max_overlap() {
    return max_overlap;
  }

  /** @return the minimum directory capacity after a minimum overlap split */
  public int get_min_fanout() {
    return min_fanout;
  }

  /** @return the minimum directory capacity */
  public int getDirMinimum() {
    return super.dirMinimum;
  }

  /** @return the minimum leaf capacity */
  public int getLeafMinimum() {
    return super.leafMinimum;
  }

  /** @return the maximum directory capacity */
  public int getDirCapacity() {
    return super.dirCapacity;
  }

  /** @return the maximum leaf capacity */
  public int getLeafCapacity() {
    return super.leafCapacity;
  }

  /** @return the tree's objects' dimension */
  public int getDimensionality() {
    return dimensionality;
  }

  /** @return the number of elements in this tree */
  public long getSize() {
    return num_elements;
  }

  /**
   * Writes all supernodes to the end of the file. This is only supposed to be
   * used for a final saving of an XTree. If another page is added to this tree,
   * the supernodes written to file by this operation are over-written. Note
   * that this tree will only be completely saved after an additional call of
   * {@link #close()}.
   * 
   * @return the number of bytes written to file for this tree's supernodes
   * @throws IOException if there are any io problems when writing the tree's
   *         supernodes
   */
  public long commit() throws IOException {
    if(!(super.getFile() instanceof PersistentPageFile)) {
      throw new IllegalStateException("Trying to commit a non-persistent XTree");
    }
    long npid = super.getFile().getNextPageID();
    XTreeHeader ph = (XTreeHeader) ((PersistentPageFile<N>) super.getFile()).getHeader();
    long offset = (ph.getReservedPages() + npid) * ph.getPageSize();
    ph.setSupernode_offset(npid * ph.getPageSize());
    ph.setNumberOfElements(num_elements);
    RandomAccessFile ra_file = ((PersistentPageFile<N>) super.getFile()).getFile();
    ph.writeHeader(ra_file);
    ra_file.seek(offset);
    long nBytes = 0;
    for(Iterator<N> iterator = supernodes.values().iterator(); iterator.hasNext();) {
      N supernode = iterator.next();
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(baos);
      supernode.writeSuperNode(oos);
      oos.close();
      baos.close();
      byte[] array = baos.toByteArray();
      byte[] sn_array = new byte[getPageSize() * (int) Math.ceil((double) supernode.getCapacity() / dirCapacity)];
      if(array.length > sn_array.length) {
        throw new IllegalStateException("Supernode is too large for fitting in " + ((int) Math.ceil((double) supernode.getCapacity() / dirCapacity)) + " pages of total size " + sn_array.length);
      }
      System.arraycopy(array, 0, sn_array, 0, array.length);
      ((PersistentPageFile<N>) super.getFile()).increaseWriteAccess();
      ra_file.write(sn_array);
      nBytes += sn_array.length;
    }
    return nBytes;
  }

  @Override
  protected TreeIndexHeader createHeader() {
    return new XTreeHeader(getPageSize(), dirCapacity, leafCapacity, dirMinimum, leafMinimum, min_fanout, num_elements, dimensionality, reinsert_fraction, max_overlap);
  }

  /**
   * Raises the "number of elements" counter.
   */
  @Override
  protected void preInsert(@SuppressWarnings("unused") E entry) {
    // TODO: can we do this somewhere else?
    num_elements++;
  }

  public boolean initializeTree(E dataObject) {
    super.initialize(dataObject);
    return true;
  }

  /**
   * To be called via the constructor if the tree is to be read from file.
   */
  @Override
  public void initializeFromFile(TreeIndexHeader hdr, PageFile<N> file) {
    XTreeHeader header = (XTreeHeader) hdr;
    super.dirCapacity = header.getDirCapacity();
    super.leafCapacity = header.getLeafCapacity();
    super.dirMinimum = header.getDirMinimum();
    super.leafMinimum = header.getLeafMinimum();
    this.min_fanout = header.getMin_fanout();
    this.num_elements = header.getNumberOfElements();
    this.dimensionality = header.getDimensionality();
    this.reinsert_fraction = header.getReinsert_fraction();
    this.max_overlap = header.getMaxOverlap();
    long superNodeOffset = header.getSupernode_offset();

    if(getLogger().isDebugging()) {
      StringBuffer msg = new StringBuffer();
      msg.append(getClass());
      msg.append("\n file = ").append(file.getClass());
      getLogger().debugFine(msg.toString());
    }

    // reset page id maintenance
    file.setNextPageID((int) (superNodeOffset / header.getPageSize()));

    // read supernodes (if there are any)
    if(superNodeOffset > 0) {
      RandomAccessFile ra_file = ((PersistentPageFile<N>) file).getFile();
      long offset = header.getReservedPages() * file.getPageSize() + superNodeOffset;
      int bs = 0 // omit this: 4 // EMPTY_PAGE or FILLED_PAGE ?
          + 4 // id
          + 1 // isLeaf
          + 1 // isSupernode
          + 4 // number of entries
          + 4; // capacity
      byte[] buffer = new byte[bs];
      try {
        // go to supernode region
        ra_file.seek(offset);
        while(ra_file.getFilePointer() + file.getPageSize() <= ra_file.length()) {
          ((PersistentPageFile<N>) file).increaseReadAccess();
          ra_file.read(buffer);
          ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(buffer));
          int id = ois.readInt();
          ois.readBoolean(); // iLeaf
          boolean supernode = ois.readBoolean();
          if(!supernode) {
            throw new IllegalStateException("Non-supernode at supernode position '" + superNodeOffset + "'");
          }
          int numEntries = ois.readInt();
          int capacity = ois.readInt();
          ois.close();
          N page;
          try {
            page = getNodeClass().newInstance();
          }
          catch(IllegalAccessException e) {
            throw new AbortException("AccessException instantiating a supernode", e);
          }
          catch(InstantiationException e) {
            throw new AbortException("InstantiationException instantiating a supernode", e);
          }
          ((PersistentPageFile<N>) file).increaseReadAccess();
          ra_file.seek(offset);
          byte[] superbuffer = new byte[file.getPageSize() * (int) Math.ceil((double) capacity / dirCapacity)];
          // increase offset for the next position seek
          offset += superbuffer.length;
          ra_file.read(superbuffer);
          ois = new ObjectInputStream(new ByteArrayInputStream(buffer));
          try {
            // read from file and add to supernode map
            page.readSuperNode(ois, this);
          }
          catch(ClassNotFoundException e) {
            throw new AbortException("ClassNotFoundException when loading a supernode", e);
          }
          assert numEntries == page.getNumEntries();
          assert capacity == page.getCapacity();
          assert id == page.getPageID();
        }
      }
      catch(IOException e) {
        throw new RuntimeException("IOException caught when loading tree from file." + e);
      }
    }

    super.initialized = true;

    // compute height
    super.height = computeHeight();

    if(getLogger().isDebugging()) {
      StringBuffer msg = new StringBuffer();
      msg.append(getClass());
      msg.append("\n height = ").append(height);
      getLogger().debugFine(msg.toString());
    }
  }

  protected abstract Class<N> getNodeClass();

  @Override
  protected void initializeCapacities(E exampleLeaf) {
    /* Simulate the creation of a leaf page to get the page capacity */
    try {
      int cap = 0;
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(baos);
      SpatialPointLeafEntry sl = new SpatialPointLeafEntry(DBIDUtil.importInteger(0), new double[exampleLeaf.getDimensionality()]);
      while(baos.size() <= getPageSize()) {
        sl.writeExternal(oos);
        oos.flush();
        cap++;
      }
      // the last one caused the page to overflow.
      leafCapacity = cap - 1;
    }
    catch(IOException e) {
      throw new AbortException("Error determining page sizes.", e);
    }

    /* Simulate the creation of a directory page to get the capacity */
    try {
      int cap = 0;
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(baos);
      HyperBoundingBox hb = new HyperBoundingBox(new double[exampleLeaf.getDimensionality()], new double[exampleLeaf.getDimensionality()]);
      XDirectoryEntry xl = new XDirectoryEntry(0, hb);
      while(baos.size() <= getPageSize()) {
        xl.writeExternal(oos);
        oos.flush();
        cap++;
      }
      dirCapacity = cap - 1;
    }
    catch(IOException e) {
      throw new AbortException("Error determining page sizes.", e);
    }

    if(dirCapacity <= 1) {
      throw new IllegalArgumentException("Node size of " + getPageSize() + " Bytes is chosen too small!");
    }

    if(dirCapacity < 10) {
      getLogger().warning("Page size is choosen very small! Maximum number of entries " + "in a directory node = " + (dirCapacity - 1));
    }

    // minimum entries per directory node
    dirMinimum = (int) Math.round((dirCapacity - 1) * relativeMinEntries);
    if(dirMinimum < 2) {
      dirMinimum = 2;
    }

    // minimum entries per directory node
    min_fanout = (int) Math.round((dirCapacity - 1) * relativeMinFanout);
    if(min_fanout < 2) {
      min_fanout = 2;
    }

    if(leafCapacity <= 1) {
      throw new IllegalArgumentException("Node size of " + getPageSize() + " Bytes is chosen too small!");
    }

    if(leafCapacity < 10) {
      getLogger().warning("Page size is choosen very small! Maximum number of entries " + "in a leaf node = " + (leafCapacity - 1));
    }

    // minimum entries per leaf node
    leafMinimum = (int) Math.round((leafCapacity - 1) * relativeMinEntries);
    if(leafMinimum < 2) {
      leafMinimum = 2;
    }

    dimensionality = exampleLeaf.getDimensionality();

    if(getLogger().isVerbose()) {
      getLogger().verbose("Directory Capacity:  " + (dirCapacity - 1) + "\nDirectory minimum: " + dirMinimum + "\nLeaf Capacity:     " + (leafCapacity - 1) + "\nLeaf Minimum:      " + leafMinimum + "\nminimum fanout: " + min_fanout);
    }
  }

  /**
   * Chooses the best path of the specified subtree for insertion of the given
   * MBR at the specified level. The selection uses the following criteria:
   * <ol>
   * <li>Test on containment (<code>mbr</code> <em>is</em> within one of the
   * children)</li>
   * <li>If there are multiple containing children, the child with the minimum
   * volume is chosen.</li>
   * <li>Else, if the children point to leaf nodes, chooses the child with the
   * minimum multi-overlap increase.</li>
   * <li>Else, or the multi-overlap increase leads to ties, the child with the
   * minimum volume increase is selected.</li>
   * <li>If there are still ties, the child with the minimum volume is chosen.</li>
   * </ol>
   * 
   * @param subtree the subtree to be tested for insertion
   * @param mbr the MBR to be inserted
   * @param level the level at which the MBR should be inserted (level 1
   *        indicates leaf-level)
   * @return the path of the appropriate subtree to insert the given
   *         <code>mbr</code>
   */
  @Override
  protected IndexTreePath<E> choosePath(IndexTreePath<E> subtree, SpatialComparable mbr, int level) {
    if(getLogger().isDebuggingFiner()) {
      getLogger().debugFiner("node " + subtree + ", level " + level);
    }

    N node = getNode(subtree.getLastPathComponent().getEntry());
    if(node.isLeaf()) {
      return subtree;
    }
    // first test on containment
    TreeIndexPathComponent<E> containingEntry = containedTest(node, mbr);
    if(containingEntry != null) {
      IndexTreePath<E> newSubtree = subtree.pathByAddingChild(containingEntry);
      if(height - subtree.getPathCount() == level) {
        return newSubtree;
      }
      else {
        return choosePath(newSubtree, mbr, level);
      }
    }

    TreeIndexPathComponent<E> optEntry = null;
    HyperBoundingBox optTestMBR = null;
    double optOverlapInc = 0;
    boolean isLeafContainer = false; // test overlap increase?
    if((!OMIT_OVERLAP_INCREASE_4_SUPERNODES // also test supernodes
    || (OMIT_OVERLAP_INCREASE_4_SUPERNODES && !node.isSuperNode())) // don't
        && getNode(node.getEntry(0)).isLeaf()) { // children are leafs
      // overlap increase is to be tested
      optOverlapInc = Double.POSITIVE_INFINITY;
      isLeafContainer = true;
    }
    double optVolume = Double.POSITIVE_INFINITY;
    double optVolumeInc = Double.POSITIVE_INFINITY;
    double tempVolume, volume;

    int index = 0;
    List<E> entries = node.getChildren();
    for(Iterator<E> iterator = entries.iterator(); iterator.hasNext(); index++) {
      E child = iterator.next();
      SpatialComparable childMBR = child;
      HyperBoundingBox testMBR = SpatialUtil.union(childMBR, mbr);
      double pairwiseOverlapInc;
      if(isLeafContainer) {
        pairwiseOverlapInc = calculateOverlapIncrease(entries, child, testMBR);
        if(Double.isInfinite(pairwiseOverlapInc) || Double.isNaN(pairwiseOverlapInc)) {
          throw new IllegalStateException("an entry's MBR is too large to calculate its overlap increase: " + pairwiseOverlapInc + "; \nplease re-scale your data s.t. it can be dealt with");
        }
      }
      else {
        // no need to examine overlap increase?
        pairwiseOverlapInc = 0;
      }

      if(pairwiseOverlapInc <= optOverlapInc) {
        if(pairwiseOverlapInc == optOverlapInc) {
          // If there are multiple entries with the same overlap increase,
          // choose the one with the minimum volume increase.
          // If there are also multiple entries with the same volume increase
          // choose the one with the minimum volume.
          volume = SpatialUtil.volume(childMBR);
          if(Double.isInfinite(volume) || Double.isNaN(volume)) {
            throw new IllegalStateException("an entry's MBR is too large to calculate its volume: " + volume + "; \nplease re-scale your data s.t. it can be dealt with");
          }
          tempVolume = SpatialUtil.volume(testMBR);
          if(Double.isInfinite(tempVolume) || Double.isNaN(tempVolume)) {
            throw new IllegalStateException("an entry's MBR is too large to calculate its volume: " + tempVolume + "; \nplease re-scale your data s.t. it can be dealt with");
          }
          double volumeInc = tempVolume - volume;

          if(Double.isNaN(optVolumeInc)) { // has not yet been calculated
            optVolume = SpatialUtil.volume(optEntry.getEntry());
            optVolumeInc = SpatialUtil.volume(optTestMBR) - optVolume;
          }
          if(volumeInc < optVolumeInc) {
            optVolumeInc = volumeInc;
            optVolume = volume;
            optEntry = new TreeIndexPathComponent<E>(child, index);
          }
          else if(volumeInc == optVolumeInc && volume < optVolume) {
            // TODO: decide whether to remove this option
            System.out.println("####\nEQUAL VOLUME INCREASE: HAPPENS!\n####");
            optVolumeInc = volumeInc;
            optVolume = volume;
            optEntry = new TreeIndexPathComponent<E>(child, index);
          }
        }
        else { // already better
          optOverlapInc = pairwiseOverlapInc;
          optVolume = Double.NaN;
          optVolumeInc = Double.NaN;
          optTestMBR = testMBR; // for later calculations
          optEntry = new TreeIndexPathComponent<E>(child, index);
        }
      }
    }
    assert optEntry != null;
    IndexTreePath<E> newSubtree = subtree.pathByAddingChild(optEntry);
    if(height - subtree.getPathCount() == level) {
      return newSubtree;
    }
    else {
      return choosePath(newSubtree, mbr, level);
    }
  }

  /**
   * Celebrated by the Profiler as a lot faster than the previous variant: that
   * used to calculate all overlaps of the old MBR and the new MBR with all
   * other MBRs. Now: The overlaps are only calculated if necessary:<br>
   * <ul>
   * <li>the new MBR does not have to be tested on overlaps if the current
   * dimension has never changed</li>
   * <li>the old MBR does not have to be tested if the new MBR shows no overlaps
   * </li>
   * </ul>
   * Furthermore tries to avoid rounding errors arising from large value ranges
   * and / or larger dimensions. <br>
   * <br>
   * However: hardly any difference in real runtime!
   * 
   * @param entries entries to be tested on overlap
   * @param ei current entry
   * @param testMBR extended MBR of <code>ei</code>
   * @return
   */
  private double calculateOverlapIncrease(List<E> entries, E ei, SpatialComparable testMBR) {
    ModifiableHyperBoundingBox eiMBR = new ModifiableHyperBoundingBox(ei);
    ModifiableHyperBoundingBox testMBRModifiable = new ModifiableHyperBoundingBox(testMBR);

    double[] lb = eiMBR.getMinRef();
    double[] ub = eiMBR.getMaxRef();
    double[] lbT = testMBRModifiable.getMinRef();
    double[] ubT = testMBRModifiable.getMaxRef();
    double[] lbNext = null; // next tested lower bounds
    double[] ubNext = null; // and upper bounds
    boolean[] dimensionChanged = new boolean[lb.length];

    for(int i = 0; i < dimensionChanged.length; i++) {
      if(lb[i] > lbT[i] || ub[i] < ubT[i]) {
        dimensionChanged[i] = true;
      }
    }

    double multiOverlapInc = 0, multiOverlapMult = 1, mOOld = 1, mONew = 1;
    double ol, olT; // dimensional overlap
    for(E ej : entries) {
      if(!getPageID(ej).equals(getPageID(ei))) {
        multiOverlapMult = 1; // is constant for a unchanged dimension
        mOOld = 1; // overlap for old MBR on changed dimensions
        mONew = 1; // overlap on new MBR on changed dimension
        ModifiableHyperBoundingBox ejMBR = new ModifiableHyperBoundingBox(ej);
        lbNext = ejMBR.getMinRef();
        ubNext = ejMBR.getMaxRef();
        for(int i = 0; i < dimensionChanged.length; i++) {
          if(dimensionChanged[i]) {
            if(lbT[i] > ubNext[i] || ubT[i] < lbNext[i]) {
              multiOverlapMult = 0;
              break; // old MBR has no overlap either
            }
            olT = (ubT[i] > ubNext[i] ? ubNext[i] : ubT[i]) - (lbT[i] < lbNext[i] ? lbNext[i] : lbT[i]);
            mONew *= olT;
            if(mOOld != 0) { // else: no use in calculating overlap
              ol = (ub[i] > ubNext[i] ? ubNext[i] : ub[i]) - (lb[i] < lbNext[i] ? lbNext[i] : lb[i]);
              if(ol < 0) {
                ol = 0;
              }
              mOOld *= ol;
            }
          }
          else {
            if(lb[i] > ubNext[i] || ub[i] < lbNext[i]) {
              multiOverlapMult = 0;
              break;
            }
            ol = (ub[i] > ubNext[i] ? ubNext[i] : ub[i]) - (lb[i] < lbNext[i] ? lbNext[i] : lb[i]);
            multiOverlapMult *= ol;
          }
        }
        if(multiOverlapMult != 0) {
          multiOverlapInc += multiOverlapMult * (mONew - mOOld);
        }
      }
    }
    return multiOverlapInc;
  }

  /**
   * Splits the specified node and returns the newly created split node. If, due
   * to an exceeding overlap, no split can be conducted, <code>node</code> is
   * converted into a supernode and <code>null</code> is returned.
   * 
   * @param node the node to be split
   * @param splitAxis field for collecting the split axis used for this split or
   *        <code>-1</code> if the split was not successful
   * @return Either the newly created split node with its split dimension logged
   *         in <code>splitAxis</code>, or <code>null</code>, if
   *         <code>node</code> has been converted into a supernode.
   */
  private N split(N node, int[] splitAxis) {
    if(splitAxis.length != 1) {
      throw new IllegalArgumentException("Expecting integer container for returning the split axis");
    }
    // choose the split dimension and the split point

    XSplitter splitter = new XSplitter(this, node.getChildren());
    XSplitter.SplitSorting split = splitter.topologicalSplit();
    double minOv = splitter.getPastOverlap();
    if(split == null) { // topological split failed
      if(node.isLeaf()) {
        throw new IllegalStateException("The topological split must be successful in leaves.");
      }
      split = splitter.minimumOverlapSplit();
      if(splitter.getPastOverlap() < minOv) {
        minOv = splitter.getPastOverlap(); // only used for logging
      }
    }
    if(split != null) {// do the split
      N newNode;
      if(node.isLeaf()) {
        newNode = createNewLeafNode();
      }
      else {
        newNode = createNewDirectoryNode();
      }
      node.splitTo(newNode, split.getSortedEntries(), split.getSplitPoint());
      // write changes to file
      writeNode(node);
      writeNode(newNode);

      splitAxis[0] = split.getSplitAxis();
      if(getLogger().isDebugging()) {
        StringBuffer msg = new StringBuffer();
        msg.append("Split Node ").append(node.getPageID()).append(" (").append(getClass()).append(")\n");
        msg.append("      splitAxis ").append(splitAxis[0]).append("\n");
        msg.append("      splitPoint ").append(split.getSplitPoint()).append("\n");
        msg.append("      newNode ").append(newNode.getPageID()).append("\n");
        if(getLogger().isVerbose()) {
          msg.append("      first: ").append(newNode.getChildren()).append("\n");
          msg.append("      second: ").append(node.getChildren()).append("\n");
        }
        getLogger().debugFine(msg.toString());
      }
      return newNode;
    }
    else { // create supernode
      node.makeSuperNode();
      supernodes.put((long) node.getPageID(), node);
      writeNode(node);
      splitAxis[0] = -1;
      if(getLogger().isDebugging()) {
        StringBuffer msg = new StringBuffer();
        msg.append("Created Supernode ").append(node.getPageID()).append(" (").append(getClass()).append(")\n");
        msg.append("      new capacity ").append(node.getCapacity()).append("\n");
        msg.append("      minimum overlap: ").append(minOv).append("\n");
        getLogger().debugFine(msg.toString());
      }
      return null;
    }
  }

  /**
   * Treatment of overflow in the specified node: if the node is not the root
   * node and this is the first call of overflowTreatment in the given level
   * during insertion the specified node will be reinserted, otherwise the node
   * will be split.
   * 
   * @param node the node where an overflow occurred
   * @param path the path to the specified node
   * @param splitAxis field for collecting the split axis used for this split or
   *        <code>-1</code> if the split was not successful
   * @return In case of a re-insertion: <code>null</code>. In case of a split:
   *         Either the newly created split node with its split dimension logged
   *         in <code>splitAxis</code>, or <code>null</code>, if
   *         <code>node</code> has been converted into a supernode.
   */
  private N overflowTreatment(N node, IndexTreePath<E> path, int[] splitAxis) {
    if(node.isSuperNode()) {
      // only extend supernode; no re-insertions
      assert node.getCapacity() == node.getNumEntries();
      assert node.getCapacity() > dirCapacity;
      node.growSuperNode();
      return null;
    }
    int level = height - path.getPathCount() + 1;
    Boolean reInsert = reinsertions.get(level);

    // there was still no reinsert operation at this level
    if(node.getPageID() != 0 && (reInsert == null || !reInsert) && reinsert_fraction != 0) {
      reinsertions.put(level, true);
      if(getLogger().isDebugging()) {
        getLogger().debugFine("REINSERT " + reinsertions);
      }

      reInsert(node, level, path);
      return null;
    }

    // there was already a reinsert operation at this level
    else {
      return split(node, splitAxis);
    }
  }

  // /**
  // * Compute the centroid of the MBRs or data objects contained by
  // * <code>node</code>. Was intended to lead to more central re-insert
  // * distributions, however, this variant rarely avoids a supernode, and
  // * definitely costs more time.
  // *
  // * @param node
  // * @return
  // */
  // protected O compute_centroid(N node) {
  // double[] d = new double[node.getDimensionality()];
  // for(int i = 0; i < node.getNumEntries(); i++) {
  // if(node.isLeaf()) {
  // double[] values = ((SpatialLeafEntry) node.getEntry(i)).getValues();
  // for(int j = 0; j < values.length; j++) {
  // d[j] += values[j];
  // }
  // }
  // else {
  // ModifiableHyperBoundingBox mbr = new
  // ModifiableHyperBoundingBox(node.getEntry(i).getMBR());
  // double[] min = mbr.getMinRef();
  // double[] max = mbr.getMaxRef();
  // for(int j = 0; j < min.length; j++) {
  // d[j] += min[j] + max[j];
  // }
  // }
  // }
  // for(int j = 0; j < d.length; j++) {
  // if(node.isLeaf()) {
  // d[j] /= node.getNumEntries();
  // }
  // else {
  // d[j] /= (node.getNumEntries() * 2);
  // }
  // }
  // // FIXME: make generic (or just hope DoubleVector is fine)
  // return (O) new DoubleVector(d);
  // }

  /**
   * Reinserts the specified node at the specified level.
   * 
   * @param node the node to be reinserted
   * @param level the level of the node
   * @param path the path to the node
   */
  @Override
  @SuppressWarnings("unchecked")
  protected void reInsert(N node, int level, IndexTreePath<E> path) {
    SquaredEuclideanDistanceFunction distFunction = SquaredEuclideanDistanceFunction.STATIC;
    DistanceEntry<DoubleDistance, E>[] reInsertEntries = new DistanceEntry[node.getNumEntries()];

    // O centroid = compute_centroid(node);

    // compute the center distances of entries to the node and sort it
    // in decreasing order to their distances
    for(int i = 0; i < node.getNumEntries(); i++) {
      E entry = node.getEntry(i);
      DoubleDistance dist = distFunction.centerDistance(node, entry);
      // DoubleDistance dist = distFunction.maxDist(entry.getMBR(), centroid);
      // DoubleDistance dist = distFunction.centerDistance(entry.getMBR(),
      // centroid);
      reInsertEntries[i] = new DistanceEntry<DoubleDistance, E>(entry, dist, i);
    }
    Arrays.sort(reInsertEntries, Collections.reverseOrder());

    // define how many entries will be reinserted
    int start = (int) (reinsert_fraction * node.getNumEntries());

    if(getLogger().isDebugging()) {
      getLogger().debugFine("reinserting " + node.getPageID() + " ; from 0 to " + (start - 1));
    }

    // initialize the reinsertion operation: move the remaining entries
    // forward
    node.initReInsert(start, reInsertEntries);
    writeNode(node);

    // and adapt the mbrs
    IndexTreePath<E> childPath = path;
    N child = node;
    while(childPath.getParentPath() != null) {
      N parent = getNode(childPath.getParentPath().getLastPathComponent().getEntry());
      int indexOfChild = childPath.getLastPathComponent().getIndex();
      child.adjustEntry(parent.getEntry(indexOfChild));
      writeNode(parent);
      childPath = childPath.getParentPath();
      child = parent;
    }

    // reinsert the first entries
    for(int i = 0; i < start; i++) {
      DistanceEntry<DoubleDistance, E> re = reInsertEntries[i];
      if(getLogger().isDebugging()) {
        getLogger().debugFine("reinsert " + re.getEntry() + (node.isLeaf() ? "" : " at " + level));
      }
      insertEntry(re.getEntry(), level);
    }
  }

  /**
   * Inserts the specified entry at the specified level into this R*-Tree.
   * 
   * @param entry the entry to be inserted
   * @param level the level at which the entry is to be inserted; automatically
   *        set to <code>1</code> for leaf entries
   */
  private void insertEntry(E entry, int level) {
    if(entry.isLeafEntry()) {
      insertLeafEntry(entry);
    }
    else {
      insertDirectoryEntry(entry, level);
    }
  }

  /**
   * Inserts the specified leaf entry into this R*-Tree.
   * 
   * @param entry the leaf entry to be inserted
   */
  @Override
  protected void insertLeafEntry(E entry) {
    // choose subtree for insertion
    IndexTreePath<E> subtree = choosePath(getRootPath(), entry, 1);

    if(getLogger().isDebugging()) {
      getLogger().debugFine("insertion-subtree " + subtree + "\n");
    }

    N parent = getNode(subtree.getLastPathComponent().getEntry());
    parent.addLeafEntry(entry);
    writeNode(parent);

    // since adjustEntry is expensive, try to avoid unnecessary subtree updates
    if(!hasOverflow(parent) && // no overflow treatment
    (isRoot(parent) || // is root
    // below: no changes in the MBR
    SpatialUtil.contains(subtree.getLastPathComponent().getEntry(), ((SpatialPointLeafEntry) entry).getValues()))) {
      return; // no need to adapt subtree
    }

    // adjust the tree from subtree to root
    adjustTree(subtree);
  }

  /**
   * Inserts the specified directory entry at the specified level into this
   * R*-Tree.
   * 
   * @param entry the directory entry to be inserted
   * @param level the level at which the directory entry is to be inserted
   */
  @Override
  protected void insertDirectoryEntry(E entry, int level) {
    // choose node for insertion of o
    IndexTreePath<E> subtree = choosePath(getRootPath(), entry, level);
    if(getLogger().isDebugging()) {
      getLogger().debugFine("subtree " + subtree);
    }

    N parent = getNode(subtree.getLastPathComponent().getEntry());
    parent.addDirectoryEntry(entry);
    writeNode(parent);

    // since adjustEntry is expensive, try to avoid unnecessary subtree updates
    if(!hasOverflow(parent) && // no overflow treatment
    (isRoot(parent) || // is root
    // below: no changes in the MBR
    SpatialUtil.contains(subtree.getLastPathComponent().getEntry(), entry))) {
      return; // no need to adapt subtree
    }

    // adjust the tree from subtree to root
    adjustTree(subtree);
  }

  /**
   * Adjusts the tree after insertion of some nodes.
   * 
   * @param subtree the subtree to be adjusted
   */
  @Override
  protected void adjustTree(IndexTreePath<E> subtree) {
    if(getLogger().isDebugging()) {
      getLogger().debugFine("Adjust tree " + subtree);
    }

    // get the root of the subtree
    N node = getNode(subtree.getLastPathComponent().getEntry());

    // overflow in node
    if(hasOverflow(node)) {
      if(node.isSuperNode()) {
        int new_capacity = node.growSuperNode();
        getLogger().finest("Extending supernode to new capacity " + new_capacity);
        if(isRoot(node)) { // is root
          node.adjustEntry(getRootEntry());
        }
        else {
          N parent = getNode(subtree.getParentPath().getLastPathComponent().getEntry());
          E e = parent.getEntry(subtree.getLastPathComponent().getIndex());
          HyperBoundingBox mbr = new HyperBoundingBox(e);
          node.adjustEntry(e);

          if(!SpatialUtil.equals(mbr, e)) { // MBR has changed
            // write changes in parent to file
            writeNode(parent);
            adjustTree(subtree.getParentPath());
          }
        }
      }
      else {
        int[] splitAxis = { -1 };
        // treatment of overflow: reinsertion or split
        N split = overflowTreatment(node, subtree, splitAxis);

        // node was split
        if(split != null) {
          // if the root was split: create a new root containing the two
          // split nodes
          if(isRoot(node)) {
            IndexTreePath<E> newRootPath = createNewRoot(node, split, splitAxis[0]);
            height++;
            adjustTree(newRootPath);
          }
          // node is not root
          else {
            // get the parent and add the new split node
            N parent = getNode(subtree.getParentPath().getLastPathComponent().getEntry());
            if(getLogger().isDebugging()) {
              getLogger().debugFine("parent " + parent);
            }
            E newEntry = createNewDirectoryEntry(split);
            parent.addDirectoryEntry(newEntry);

            // The below variant does not work in the persistent version
            // E oldEntry = subtree.getLastPathComponent().getEntry();
            // [reason: if oldEntry is modified, this must be permanent]
            E oldEntry = parent.getEntry(subtree.getLastPathComponent().getIndex());

            // adjust split history
            SplitHistory sh = ((SplitHistorySpatialEntry) oldEntry).getSplitHistory();
            if(sh == null) {
              // not yet initialized (dimension not known of this tree)
              sh = new SplitHistory(oldEntry.getDimensionality());
              sh.setDim(splitAxis[0]);
              ((SplitHistorySpatialEntry) oldEntry).setSplitHistory(sh);
            }
            else {
              ((SplitHistorySpatialEntry) oldEntry).addSplitDimension(splitAxis[0]);
            }
            try {
              ((SplitHistorySpatialEntry) newEntry).setSplitHistory((SplitHistory) sh.clone());
            }
            catch(CloneNotSupportedException e) {
              throw new RuntimeException("Clone of a split history should not throw an Exception", e);
            }

            // adjust the entry representing the (old) node, that has
            // been split
            node.adjustEntry(oldEntry);

            // write changes in parent to file
            writeNode(parent);
            adjustTree(subtree.getParentPath());
          }
        }
      }
    }
    // no overflow, only adjust parameters of the entry representing the
    // node
    else {
      if(isRoot(node)) {
        N parent = getNode(subtree.getParentPath().getLastPathComponent().getEntry());
        E e = parent.getEntry(subtree.getLastPathComponent().getIndex());
        HyperBoundingBox mbr = new HyperBoundingBox(e);
        node.adjustEntry(e);

        if(node.isLeaf() || // we already know that mbr is extended
        !SpatialUtil.equals(mbr, e)) { // MBR has changed
          // write changes in parent to file
          writeNode(parent);
          adjustTree(subtree.getParentPath());
        }
      }
      // root level is reached
      else {
        node.adjustEntry(getRootEntry());
      }
    }
  }

  /**
   * Creates a new root node that points to the two specified child nodes and
   * return the path to the new root.
   * 
   * @param oldRoot the old root of this RTree
   * @param newNode the new split node
   * @param splitAxis the split axis used for the split causing this new root
   * @return the path to the new root node that points to the two specified
   *         child nodes
   */
  protected IndexTreePath<E> createNewRoot(final N oldRoot, final N newNode, int splitAxis) {
    N root = createNewDirectoryNode();
    writeNode(root);
    // get split history
    SplitHistory sh = null;
    // TODO: see whether root entry is ALWAYS a directory entry .. it SHOULD!
    sh = ((XDirectoryEntry) getRootEntry()).getSplitHistory();
    if(sh == null) {
      sh = new SplitHistory(oldRoot.getDimensionality());
    }
    sh.setDim(splitAxis);

    // switch the ids
    oldRoot.setPageID(root.getPageID());
    if(!oldRoot.isLeaf()) {
      // TODO: test whether this is neccessary
      for(int i = 0; i < oldRoot.getNumEntries(); i++) {
        N node = getNode(oldRoot.getEntry(i));
        writeNode(node);
      }
    }
    // adjust supernode id
    if(oldRoot.isSuperNode()) {
      supernodes.remove(new Long(getRootID()));
      supernodes.put(new Long(oldRoot.getPageID()), oldRoot);
    }

    root.setPageID(getRootID());
    E oldRootEntry = createNewDirectoryEntry(oldRoot);
    E newNodeEntry = createNewDirectoryEntry(newNode);
    ((SplitHistorySpatialEntry) oldRootEntry).setSplitHistory(sh);
    try {
      ((SplitHistorySpatialEntry) newNodeEntry).setSplitHistory((SplitHistory) sh.clone());
    }
    catch(CloneNotSupportedException e) {
      throw new RuntimeException("Clone of a split history should not throw an Exception", e);
    }
    root.addDirectoryEntry(oldRootEntry);
    root.addDirectoryEntry(newNodeEntry);

    writeNode(root);
    writeNode(oldRoot);
    writeNode(newNode);
    if(getLogger().isDebugging()) {
      String msg = "Create new Root: ID=" + root.getPageID();
      msg += "\nchild1 " + oldRoot + " " + new HyperBoundingBox(oldRoot) + " " + new HyperBoundingBox(oldRootEntry);
      msg += "\nchild2 " + newNode + " " + new HyperBoundingBox(newNode) + " " + new HyperBoundingBox(newNodeEntry);
      msg += "\n";
      getLogger().debugFine(msg);
    }
    // the root entry still needs to be set to the new root node's MBR
    return new IndexTreePath<E>(new TreeIndexPathComponent<E>(getRootEntry(), null));
  }

  /**
   * Returns a string representation of this XTree.
   * 
   * @return a string representation of this XTree
   */
  @Override
  public String toString() {
    StringBuffer result = new StringBuffer();
    long dirNodes = 0;
    long superNodes = 0;
    long leafNodes = 0;
    long objects = 0;
    long maxSuperCapacity = -1;
    long minSuperCapacity = Long.MAX_VALUE;
    BigInteger totalCapacity = BigInteger.ZERO;
    int levels = 0;

    N node = getRoot();

    while(!node.isLeaf()) {
      if(node.getNumEntries() > 0) {
        E entry = node.getEntry(0);
        node = getNode(entry);
        levels++;
      }
    }

    BreadthFirstEnumeration<N, E> enumeration = new BreadthFirstEnumeration<N, E>(this, getRootPath());
    while(enumeration.hasMoreElements()) {
      IndexTreePath<E> indexPath = enumeration.nextElement();
      E entry = indexPath.getLastPathComponent().getEntry();
      if(entry.isLeafEntry()) {
        objects++;
      }
      else {
        node = getNode(entry);
        if(node.isLeaf()) {
          leafNodes++;
        }
        else {
          if(node.isSuperNode()) {
            superNodes++;
            if(node.getCapacity() > maxSuperCapacity) {
              maxSuperCapacity = node.getCapacity();
            }
            if(node.getCapacity() < minSuperCapacity) {
              minSuperCapacity = node.getCapacity();
            }
          }
          else {
            dirNodes++;
          }
        }
        totalCapacity = totalCapacity.add(BigInteger.valueOf(node.getCapacity()));
      }
    }
    assert objects == num_elements : "objects=" + objects + ", size=" + num_elements;
    result.append(getClass().getName()).append(" has ").append((levels + 1)).append(" levels.\n");
    result.append(dirNodes).append(" Directory Nodes (max = ").append(dirCapacity - 1).append(", min = ").append(dirMinimum).append(")\n");
    result.append(superNodes).append(" Supernodes (max = ").append(maxSuperCapacity - 1).append(", min = ").append(minSuperCapacity - 1).append(")\n");
    result.append(leafNodes).append(" Data Nodes (max = ").append(leafCapacity - 1).append(", min = ").append(leafMinimum).append(")\n");
    result.append(objects).append(" ").append(dimensionality).append("-dim. points in the tree \n");
    result.append("min_fanout = ").append(min_fanout).append(", max_overlap = ").append(max_overlap).append((this.overlap_type == DATA_OVERLAP ? " data overlap" : " volume overlap")).append(", re_inserts = ").append(reinsert_fraction + "\n");
    PageFileUtil.appendPageFileStatistics(result, getPageFileStatistics());
    result.append("Storage Quota ").append(BigInteger.valueOf(objects + dirNodes + superNodes + leafNodes).multiply(BigInteger.valueOf(100)).divide(totalCapacity).toString()).append("%\n");

    return result.toString();
  }
}