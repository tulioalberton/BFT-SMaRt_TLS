/**
Copyright (c) 2019-2019 Tulio Alberton Ribeiro

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

package bftsmart.tree;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

import bftsmart.communication.ServerCommunicationSystem;
import bftsmart.consensus.messages.ConsensusMessage;
import bftsmart.reconfiguration.ServerViewController;
import bftsmart.tom.util.TOMUtil;
import bftsmart.tree.messages.TreeMessage;
import bftsmart.tree.messages.TreeMessage.TreeOperationType;

public class TreeManager {

	private ServerCommunicationSystem commS = null;
	private ServerViewController SVController = null;
	// private ReentrantLock lock = new ReentrantLock();

	private int replicaId;
	private int currentLeader = -1;
	private int parent;
	private List<Integer> children;
	private Queue<Integer> unexplored;
	private boolean finish = false;

	// Constructor.
	public TreeManager(ServerCommunicationSystem commS, ServerViewController SVController, int leader) {
		this.commS = commS;
		this.SVController = SVController;
		this.replicaId = SVController.getStaticConf().getProcessId();
		this.parent = -1;
		this.currentLeader = leader;
		this.unexplored = new LinkedList<Integer>();

		// int[] neighbors = SVController.getCurrentViewOtherAcceptors();
		Integer[] neighbors = Arrays.stream(SVController.getCurrentViewOtherAcceptors()).boxed()
				.toArray(Integer[]::new);
		Collections.shuffle(Arrays.asList(neighbors), new Random());
		for (int i = 0; i < neighbors.length; i++) {
			this.unexplored.add(neighbors[i]);
		}
		this.children = new LinkedList<Integer>();
	}

	@Override
	public String toString() {
		String appended = "\nReplicaId: " + replicaId + "";
		appended += "\nParent: " + parent + "\n";
		//appended += "Current Leader: " + this.currentLeader + "\n";

		/*appended += "Unexplored: \n";
		Iterator<Integer> it = this.unexplored.iterator();
		while (it.hasNext()) {
			appended += "\t " + (Integer) it.next() + " \n";
		}*/
		if (!this.children.isEmpty()) {
			appended += "Children: \n";
			Iterator<Integer> it = this.children.iterator();
			while (it.hasNext()) {
				appended += "\t " + (Integer) it.next() + " \n";
			}
		}else {
			appended += "I am a leaf.";
		}

		return appended;
	}

	public boolean initProtocol() {
		if (this.replicaId == this.currentLeader && parent == -1) {
			this.parent = replicaId;
			System.out.println("Spanning Tree initialized by root.\n" + toString());
			explore();
			/*
			 * TreeMessage tm = new TreeMessage(this.replicaId, TreeOperationType.M); while
			 * (!this.unexplored.isEmpty()) { int toSend = this.unexplored.poll();
			 * System.out.println("Sending M message to: " + toSend); commS.send(new int[] {
			 * toSend }, signedMessage(tm)); Random rand = new Random(); try {
			 * Thread.sleep(rand.nextInt(10)); } catch (InterruptedException e) { // TODO
			 * Auto-generated catch block e.printStackTrace(); } }
			 */
			return true;
		} else {
			return false;
		}
	}

	private void explore() {
		if (!this.unexplored.isEmpty()) {
			TreeMessage tm = new TreeMessage(this.replicaId, TreeOperationType.M);
			int toSend = this.unexplored.poll();
			// System.out.println("Sending M message to: " + toSend);
			commS.send(new int[] { toSend }, signedMessage(tm));
		} else {
			if (this.parent != this.replicaId) {
				TreeMessage tm = new TreeMessage(this.replicaId, TreeOperationType.PARENT);
				commS.send(new int[] { this.parent }, signedMessage(tm));
				// System.out.println("Finished Spanning Tree: \n" + toString());
				// this.finish = true;
				receivedFinished(new TreeMessage(this.replicaId, TreeOperationType.FINISHED));
			}
		}
	}

	public void receivedM(TreeMessage msg) {
		if (this.parent == -1) {
			this.parent = msg.getSender();
			this.unexplored.remove(msg.getSender());
			// System.out.println("Defining "+msg.getSender()+" as my patent.");
			explore();
		} else {
			TreeMessage tm = new TreeMessage(replicaId, TreeOperationType.ALREADY);
			commS.send(new int[] { msg.getSender() }, signedMessage(tm));
			this.unexplored.remove(msg.getSender());
			// System.out.println("Sending ALREADY msg to: "+msg.getSender());
		}
	}

	public void receivedAlready(TreeMessage msg) {
		explore();
	}

	public void receivedParent(TreeMessage msg) {
		System.out.println("Adding " + msg.getSender() + " as a child.");
		if (!this.children.contains(msg.getSender()))
			this.children.add(msg.getSender());
		explore();
	}

	public void receivedFinished(TreeMessage msg) {
		if (!this.finish) {
			System.out.println("Finished spanning tree, SpanningTree:\n" + toString());
			this.finish = true;
			if (replicaId != parent) {
				TreeMessage tm = new TreeMessage(replicaId, TreeOperationType.FINISHED);
				commS.send(new int[] { parent }, signedMessage(tm));
			}
		}
	}

	public TreeMessage signedMessage(TreeMessage tm) {
		Signature eng;
		try {
			eng = TOMUtil.getSigEngine();
			eng.initSign(SVController.getStaticConf().getPrivateKey());
			eng.update(tm.toString().getBytes());
			tm.setSignature(eng.sign());
		} catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException e) {
			e.printStackTrace();
		}
		return tm;
	}

	public List<Integer> getChildren() {
		return this.children;
	}

	public int getParent() {
		return this.parent;
	}

	public boolean getFinish() {
		return this.finish;
	}

	public void forwardToParent(ConsensusMessage consMsg) {
		if(this.parent != replicaId)
			commS.send(new int[] { this.parent }, consMsg);
	}

	public void forwardToChildren(ConsensusMessage consMsg) {
		if (this.children.isEmpty()) {
			System.out.println("I have no children: ");
			return;
		}
		Iterator<Integer> it = this.children.iterator();
		while (it.hasNext()) {
			Integer child = (Integer) it.next();
			System.out.println("Forwarding message to children: "+child+", Msg: " + consMsg.toString() );
			commS.send(new int[] { child }, consMsg);
		}
		
	}

	public synchronized void createStaticTree() {
		if (!this.finish) {
			switch (replicaId) {
			case 0:
				this.parent = 0;
				this.children.add(1);
				this.children.add(2);
				break;
			case 1:
				this.parent = 0;
				this.children.add(3);
				this.children.add(4);
				break;
			case 2:
				this.parent = 0;
				this.children.add(5);
				this.children.add(6);
				break;
			case 3:
				this.parent = 1;
				break;
			case 4:
				this.parent = 1;
				break;
			case 5:
				this.parent = 2;
				break;
			case 6:
				this.parent = 2;
				break;
			default:
				break;
			}
			this.finish = true;
			System.out.println("Stacic tree created. ");
		}
	}
}
