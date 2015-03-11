// Copyright (c) 2003 Compaq Corporation.  All rights reserved.
// Portions Copyright (c) 2003 Microsoft Corporation.  All rights reserved.
// Last modified on Mon 30 Apr 2007 at 13:33:37 PST by lamport
//      modified on Mon Nov 26 15:46:11 PST 2001 by yuanyu

package tlc2.tool.liveness;

import java.io.IOException;

import tlc2.util.BitVector;
import tlc2.util.BufferedRandomAccessFile;

public class GraphNode {
	/**
	 * GraphNode is a node in the behaviour graph. We're going to only store
	 * fingerprints of states, rather than actual states. So, as we encounter
	 * each state, we need to calculate all the <>[] and []<>'s listed in the
	 * order of solution. For each outgoing edge, we record the fingerprint of
	 * the target node and the checkActions along it.
	 *
	 * The field tidx is the unique index for the tableau graph node. If tindex
	 * = -1, then there is no tableau. So, the maximum size of tableau is 2^31.
	 */
	private final static int[] emptyIntArr = new int[0];

	long stateFP; // fingerprint of the state
	/**
	 * Next nodes are the successor {@link GraphNode}s of the current {@link GraphNode}
	 */
	private int[] nnodes; // outgoing links
	private BitVector checks; // truth values for state and action preds
	int tindex;

	public GraphNode(long fp, int tindex) {
		this(fp, tindex, emptyIntArr, new BitVector(0));
	}

	public GraphNode(long fp, int tindex, int[] nnodes, BitVector checks) {
		this.stateFP = fp;
		this.tindex = tindex;
		this.nnodes = nnodes;
		this.checks = checks;
	}

	public final boolean equals(Object obj) {
		return ((obj instanceof GraphNode) && (this.stateFP == ((GraphNode) obj).stateFP) && (this.tindex == ((GraphNode) obj).tindex));
	}

	public final long getStateFP(int i) {
		long high = this.nnodes[3 * i];
		long low = this.nnodes[3 * i + 1];
		return (high << 32) | (low & 0xFFFFFFFFL);
	}

	public final int getTidx(int i) {
		return this.nnodes[3 * i + 2];
	}

	public final int succSize() {
		return this.nnodes.length / 3;
	}

	public final boolean getCheckState(int i) {
		return this.checks.get(i);
	}

	public final boolean getCheckAction(int slen, int alen, int nodeIdx, int i) {
		int pos = slen + alen * nodeIdx + i;
		return this.checks.get(pos);
	}

	public final boolean getCheckAction(int slen, int alen, int nodeIdx, int[] is) {
		int len = is.length;
		for (int i = 0; i < len; i++) {
			int pos = slen + alen * nodeIdx + is[i];
			if (!this.checks.get(pos)) {
				return false;
			}
		}
		return true;
	}

	public final void setCheckState(boolean[] vals) {
		int len = vals.length;
		for (int i = 0; i < len; i++) {
			if (vals[i]) {
				this.checks.set(i);
			}
		}
	}

	/**
	 * Points to the first available slot in {@link GraphNode#nnodes} iff free
	 * slots are available. "-1" indicates no free slots are available.
	 * @see GraphNode#allocate(int)
	 */
	private int offset = -1;
	
	/**
	 * Allocates memory for subsequent
	 * {@link GraphNode#addTransition(long, int, int, int, boolean[])} calls.
	 * This is useful if
	 * {@link GraphNode#addTransition(long, int, int, int, boolean[])} gets
	 * invoked from within a loop when the approximate number of invocations is
	 * known in advance. In this case {@link GraphNode} can reserve the memory
	 * for the number of transitions in advance which greatly improves the
	 * insertion time of
	 * {@link GraphNode#addTransition(long, int, int, int, boolean[])}. Once all
	 * transitions have been added to via
	 * {@link GraphNode#addTransition(long, int, int, int, boolean[])},
	 * optionally call the {@link GraphNode#realign()} method to discard of
	 * unused memory.
	 * <p>
	 * Technically this essentially grows GraphNode's internal data structure.
	 * <p>
	 * Do note that you can call addTransition <em>without</em> calling allocate
	 * first. It then automatically allocates a memory for a <em>single</em>
	 * transition.
	 * 
	 * @param transitions
	 *            The approximate number of transitions that will be added
	 *            subsequently.
	 *            
	 * @see GraphNode#addTransition(long, int, int, int, boolean[])
	 * @see GraphNode#realign()           
	 */
	public final void allocate(int transitions) {
		final int len = this.nnodes.length;
		int[] newNodes = new int[len + (3 * transitions)];
		System.arraycopy(this.nnodes, 0, newNodes, 0, len);
		this.nnodes = newNodes;
		
		this.offset = len;
	}
	
	/* Add a new transition to the node target. */
	public final void addTransition(long fp, int tidx, int slen, int alen, boolean[] acts) {
		// Grows BitVector "checks" and sets the corresponding field to true if
		// acts is true (false is default and thus can be ignored).
		if (acts != null) {
			int pos = slen + alen * this.succSize();
			for (int i = 0; i < acts.length; i++) {
				if (acts[i]) {
					this.checks.set(pos + i);
				}
			}
		}
		// Increase nnodes size by one node "record"#
		if (this.offset == -1) {
			final int len = this.nnodes.length;
			int[] newNodes = new int[len + 3];
			System.arraycopy(this.nnodes, 0, newNodes, 0, len);
			newNodes[len] = (int) (fp >>> 32);
			newNodes[len + 1] = (int) (fp & 0xFFFFFFFFL);
			newNodes[len + 2] = tidx;
			this.nnodes = newNodes;
		} else {
			this.nnodes[this.offset] = (int) (fp >>> 32);
			this.nnodes[this.offset + 1] = (int) (fp & 0xFFFFFFFFL);
			this.nnodes[this.offset + 2] = tidx;
			this.offset = this.offset + 3;
			if (this.offset == this.nnodes.length) {
				this.offset = -1;
			}
		}
	}
	
	/**
	 * Trims {@link GraphNode}'s internal data structure to its current real
	 * memory requirement.
	 * 
	 * @see GraphNode#allocate(int)
	 */
	public void realign() {
		// It is a noop iff offset == -1
		if (this.offset != -1) {
			// shrink newNodes to correct size
			int[] newNodes = new int[this.offset];
			System.arraycopy(this.nnodes, 0, newNodes, 0, newNodes.length);
			this.nnodes = newNodes;
			this.offset = -1;
		}
	}
	
	public void realign(int transitionsAllocated) {
		if (this.offset != -1) {
			// Could have fitted this many extra transitions into 
			// memory
			int transitionsRemaining = (this.nnodes.length - this.offset) / 3;
			// Raise warning of overhead has been > 50%
			if((this.offset / transitionsAllocated) < 0.5d) {
				System.out.println(String.format("WARNING: overhead > 50%%. Still free: %s of %s", transitionsRemaining, transitionsAllocated));
			}
		}
		realign();
	}

	
	/* Return true iff there is an outgoing edge to target. */
	public final boolean transExists(long fp, int tidx) {
		int len = this.nnodes.length;
		int high = (int) (fp >>> 32);
		int low = (int) (fp & 0xFFFFFFFFL);
		for (int i = 0; i < len; i += 3) {
			if (this.nnodes[i] == high && this.nnodes[i + 1] == low && this.nnodes[i + 2] == tidx) {
				return true;
			}
		}
		return false;
	}

	/* Return the tableau graph node used by this. */
	public final TBGraphNode getTNode(TBGraph tableau) {
		return tableau.getNode(this.tindex);
	}

	/**
	 * Writes this {@link GraphNode} into the given {@link BufferedRandomAccessFile}
	 * @param nodeRAF
	 * @throws IOException
	 */
	void write(final BufferedRandomAccessFile nodeRAF) throws IOException {
		// Write nnodes
		final int cnt = nnodes.length;
		nodeRAF.writeNat(cnt);
		for (int i = 0; i < cnt; i++) {
			nodeRAF.writeInt(nnodes[i]);
		}
		// Write checks
		checks.write(nodeRAF);
	}

	void read(final BufferedRandomAccessFile nodeRAF) throws IOException {
		// Read nnodes
		final int cnt = nodeRAF.readNat();
		nnodes = new int[cnt];
		for (int i = 0; i < cnt; i++) {
			nnodes[i] = nodeRAF.readInt();
		}
		// Read checks
		checks = new BitVector();
		checks.read(nodeRAF);
	}

	
	public final String toString() {
		StringBuffer buf = new StringBuffer();
		buf.append("<" + this.stateFP + "," + this.tindex + "> --> ");
		int size = this.nnodes.length;
		if (size != 0) {
			long high = this.nnodes[0];
			long low = this.nnodes[1];
			long fp = (high << 32) | (low & 0xFFFFFFFFL);
			buf.append("<" + fp + "," + this.nnodes[2] + ">");
		}
		for (int i = 3; i < size; i += 3) {
			buf.append(", ");
			long high = this.nnodes[i];
			long low = this.nnodes[i + 1];
			long fp = (high << 32) | (low & 0xFFFFFFFFL);
			buf.append("<" + fp + "," + this.nnodes[i + 2] + ">");
		}
		return buf.toString();
	}

	public String toDotViz() {
		StringBuffer buf = new StringBuffer();
		int size = this.nnodes.length;
		for (int i = 0; i < size; i += 3) {
			buf.append(("" + this.stateFP).substring(0, 3) + "." + this.tindex + " -> ");
			long high = this.nnodes[i];
			long low = this.nnodes[i + 1];
			long fp = (high << 32) | (low & 0xFFFFFFFFL);
			buf.append(("" + fp).substring(0, 3) + "." + this.nnodes[i + 2]);
			buf.append("\n");
		}
		return buf.toString();
	}
}
