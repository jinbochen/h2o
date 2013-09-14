package water.fvec;

import water.*;

/** 
 * A compression scheme, over a chunk - a single array of bytes.  The *actual*
 * BigVector header info is in the Vec struct - which contains info to find all
 * the bytes of the distributed vector.  This struct is basically a 1-entry
 * chunk cache of the total vector.  Subclasses of this abstract class
 * implement (possibly empty) compression schemes.
*/

public abstract class Chunk extends Iced implements Cloneable {
  public long _start;         // Start element; filled after AutoBuffer.read
  public int _len;            // Number of elements in this chunk
  protected Chunk _chk;       // Normally==this, changed if chunk is written to
  public Vec _vec;            // Owning Vec; filled after AutoBuffer.read
  byte[] _mem; // Short-cut to the embedded memory; WARNING: holds onto a large array
  Chunk() { _chk=this; }

  public final boolean readable( ) { return _vec.readable(); }
  public final boolean writable( ) { return _vec.writable(); }
  public final byte[] getBytes() { return _mem; }

  /** Load a long value.  Floating point values are silently rounded to an
    * integer.  Throws if the value is missing.<p>
    * Loads from the 1-entry chunk cache, or misses-out.  This version uses
    * absolute element numbers, but must convert them to chunk-relative indices
    * - requiring a load from an aliasing local var, leading to lower quality
    * JIT'd code (similar issue to using iterator objects).<p>
    * Slightly slower than 'at0' since it range checks within a chunk.
    */
  public final long  at8( long i ) { 
    long x = i-_start;
    if( 0 <= x && x < _len ) return at80((int)x);
    throw new ArrayIndexOutOfBoundsException(""+_start+" <= "+i+" < "+(_start+_len));
  }

  /** Load a double value.  Returns Double.NaN if value is missing.<p>
    * Loads from the 1-entry chunk cache, or misses-out.  This version uses
    * absolute element numbers, but must convert them to chunk-relative indices
    * - requiring a load from an aliasing local var, leading to lower quality
    * JIT'd code (similar issue to using iterator objects).<p>
    * Slightly slower than 'at80' since it range checks within a chunk.
    */
  public final double at( long i ) { 
    long x = i-_start;
    if( 0 <= x && x < _len ) return at0((int)x);
    throw new ArrayIndexOutOfBoundsException(""+_start+" <= "+i+" < "+(_start+_len));
  }

  /** Fetch the missing-status the slow way. */
  public final boolean isNA(long i) {
    long x = i-_start;
    if( 0 <= x && x < _len ) return isNA0((int)x);
    throw new ArrayIndexOutOfBoundsException(""+_start+" <= "+i+" < "+(_start+_len));
  }


  /** The zero-based API.  Somewhere between 10% to 30% faster in a tight-loop
   * over the data than the generic at() API.  Probably no gain on larger
   * loops.  The row reference is zero-based on the chunk, and should
   * range-check by the JIT as expected.
   */
  public final double  at0  ( int i ) { return _chk. atd_impl(i); }
  public final long    at80 ( int i ) { return _chk. at8_impl(i); }
  public final boolean isNA0( int i ) { return _chk.isNA_impl(i); }


  /** Slightly slower than 'at0' inside a chunk; goes (very) slow outside the
   * chunk instead of throwing.  First outside-chunk fetches & caches whole
   * chunk; maybe takes multiple msecs.  2nd & later touches in the same
   * outside-chunk probably run 100x slower than inside-chunk accesses.
   */ 
  public final double at_slow( long i ) {
    long x = i-_start;
    return (0 <= x && x < _len) ? at0((int)x) : _vec.at(i);
  }
  public final long at8_slow( long i ) {
    long x = i-_start;
    return (0 <= x && x < _len) ? at80((int)x) : _vec.at8(i);
  }
  public final boolean isNA_slow( long i ) {
    long x = i-_start;
    return (0 <= x && x < _len) ? isNA0((int)x) : _vec.isNA(i);
  }

  /** Write element the slow way, as a long.  There is no way to write a
   *  missing value with this call.  Under rare circumstances this can throw:
   *  if the long does not fit in a double (value is larger magnitude than
   *  2^52), AND float values are stored in Vector.  In this case, there is no
   *  common compatible data representation. */
  public final long set( long i, long l) {
    long x = i-_start;
    return (0 <= x && x < _len) ? set0((int)x,l) : _vec.set(i,l);
  }
  /** Write element the slow way, as a double.  Double.NaN will be treated as
   *  a set of a missing element. */
  public final double set( long i, double d) { 
    long x = i-_start;
    return (0 <= x && x < _len) ? set0((int)x,d) : _vec.set(i,d);
  }
  /** Write element the slow way, as a float.  Float.NaN will be treated as
   *  a set of a missing element. */
  public final float set( long i, float f) { 
    long x = i-_start;
    return (0 <= x && x < _len) ? set0((int)x,f) : _vec.set(i,f);
  }
  /** Set the element as missing the slow way.  */
  public final boolean setNA( long i ) { 
    long x = i-_start;
    return (0 <= x && x < _len) ? setNA0((int)x) : _vec.setNA(i);
  }
  
  private void setWrite() {
    if( _chk!=this ) return;
    assert !(this instanceof NewChunk) : "Cannot direct-write into a NewChunk, only append";
    _vec.startWriting();      // One-shot writing-init
    _chk = clone();           // Flag this chunk as having been written into
    _chk._chk = _chk;         // Clone has NOT been written into
  }

  /**
   * Set a long element in a chunk given a 0-based chunk local index.
   *
   * Write into a chunk.
   * May rewrite/replace chunks if the chunk needs to be
   * "inflated" to hold larger values.  Returns the input value.
   *
   * Note that the idx is an int (instead of a long), which tells you
   * that index 0 is the first row in the chunk, not the whole Vec.
   */
  public final long set0(int idx, long l) {
    setWrite();
    if( _chk.set_impl(idx,l) ) return l;
    (_chk = inflate_impl(new NewChunk(this))).set_impl(idx,l);
    return l;
  }

  /** Set a double element in a chunk given a 0-based chunk local index. */
  public final double set0(int idx, double d) {
    setWrite();
    if( _chk.set_impl(idx,d) ) return d;
    (_chk = inflate_impl(new NewChunk(this))).set_impl(idx,d);
    return d;
  }

  /** Set a floating element in a chunk given a 0-based chunk local index. */
  public final float set0(int idx, float f) {
    setWrite();
    if( _chk.set_impl(idx,f) ) return f;
    (_chk = inflate_impl(new NewChunk(this))).set_impl(idx,f);
    return f;
  }

  /** Set the element in a chunk as missing given a 0-based chunk local index. */
  public final boolean setNA0(int idx) {
    setWrite();
    if( _chk.setNA_impl(idx) ) return true;
    (_chk = inflate_impl(new NewChunk(this))).setNA_impl(idx);
    return true;
  }


  /** After writing we must call close() to register the bulk changes */
  public void close( int cidx, Futures fs ) {
    if( _chk instanceof NewChunk )_chk = ((NewChunk)_chk).close(fs);
    if( _chk == this ) return;
    DKV.put(_vec.chunkKey(cidx),_chk,fs); // Write updated chunk back into K/V
  }

  public int cidx() { return _vec.elem2ChunkIdx(_start); }

  /** 
   * Chunk-specific readers.
   */ 
  abstract protected double   atd_impl(int idx);
  abstract protected long     at8_impl(int idx);
  abstract protected boolean isNA_impl(int idx);
  
  /** 
   * Chunk-specific writer.  Returns false if the value does not fit in the
   * current compression scheme.
   */
  abstract boolean set_impl  (int idx, long l );
  abstract boolean set_impl  (int idx, double d );
  abstract boolean set_impl  (int idx, float f );
  abstract boolean setNA_impl(int idx);

  /**
   * Chunk-specific bulk inflator back to NewChunk.  Used when writing into a
   * chunk and written value is out-of-range for an update-in-place operation.
   * Bulk copy from the compressed form into the nc._ls array.
   */ 
  abstract NewChunk inflate_impl(NewChunk nc);
  abstract boolean hasFloat();
  /** 
   * Chunk-specific implementations of read & write
   */ 
  public abstract AutoBuffer write(AutoBuffer bb);
  public abstract Chunk  read (AutoBuffer bb);

  @Override public Chunk clone() { return (Chunk)super.clone(); }
  @Override public String toString() { return getClass().getSimpleName(); }

  public long byteSize() {
    long s= _mem == null ? 0 : _mem.length;
    s += (2+5)*8 + 12; // 2 hdr words, 5 other words, @8bytes each, plus mem array hdr
    if( _chk != null && _chk != this ) s += _chk.byteSize();
    return s;
  }
}
