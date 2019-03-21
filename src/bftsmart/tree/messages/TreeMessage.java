/**
Copyright (c) 2019-2019 Tulio Alberton Ribeiro.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package bftsmart.tree.messages;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import bftsmart.communication.SystemMessage;

/**
 * This class represents a message used in the spanning tree configurations.
 */
public class TreeMessage extends SystemMessage {

	public enum TreeOperationType{
		INIT,
		RECONFIG,//not used
		NOOP,
		// messages to deal with spanning tree for a specified root. 
		M, 
		ALREADY,
		PARENT,
		FINISHED,
		STATIC_TREE;
		
		
	};
	private int viewTag;
	private byte[] signature; // signature
	private TreeOperationType treeOperation; // TreeOperationType.
	private boolean result = false; // result of the operation // OK:1:true and NOK:0:false
	
	private long timestamp;
	/**
	 * Constructors.
	 */
	public TreeMessage() {
	}

	public TreeMessage(int from, TreeOperationType treeOperation) {
		super(from);
		this.treeOperation = treeOperation;
		this.timestamp = System.nanoTime();
		this.viewTag = viewTag;
	}

	/**
	 * Externalizable implementation methods
	 */
	@Override
	public void writeExternal(ObjectOutput out) throws IOException {
		super.writeExternal(out);// write from
		out.writeObject(treeOperation);

		out.writeInt(signature.length);
		out.write(signature);
		out.writeBoolean(result);
		out.writeLong(timestamp);
		out.writeInt(viewTag);
	}

	@Override
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {

		super.readExternal(in);
		this.treeOperation = (TreeOperationType) in.readObject();

		this.signature = new byte[in.readInt()];
		in.read(this.signature);
		
		this.result = in.readBoolean();
		this.timestamp = in.readLong();
		this.viewTag = in.readInt();
	}

	/**
	 * getters and setters
	 * */ 
	
	public void setSignature(byte[] signature) {
		this.signature = signature;
	}

	public byte[] getSignature() {
		return signature;
	}

	public long getTimestamp() {
		return timestamp;
	}
	public boolean getResult() {
		return result;
	}

	public void setResult(boolean result) {
		this.result = result;
	}
	public TreeOperationType getTreeOperationType() {
		return this.treeOperation;
	}
	public int getViewTag() {
		return this.viewTag;
	}

	@Override
	public String toString() {
		return "";
	}
	
}
