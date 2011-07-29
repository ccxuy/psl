/*
 * This file is part of the PSL software.
 * Copyright 2011 University of Maryland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.umd.cs.psl.model.formula.traversal;

import java.util.*;

import edu.umd.cs.psl.database.ResultList;
import edu.umd.cs.psl.model.argument.GroundTerm;
import edu.umd.cs.psl.model.argument.Term;
import edu.umd.cs.psl.model.argument.Variable;
import edu.umd.cs.psl.model.atom.Atom;
import edu.umd.cs.psl.model.atom.AtomEventFramework;
import edu.umd.cs.psl.model.atom.AtomManager;
import edu.umd.cs.psl.model.atom.VariableAssignment;
import edu.umd.cs.psl.model.formula.*;

public class FormulaGrounder extends FormulaTraverser {

	private final AtomManager atommanger;
	private final ArrayList<Formula> stack;
	private final ResultList results;
	private final VariableAssignment varAssign;
	
	private int position;
	
	public FormulaGrounder(AtomManager m, ResultList res, VariableAssignment var) {
		assert m!=null && res!=null;
		atommanger = m;
		results = res;
		position=0;
		varAssign = var;
		stack = new ArrayList<Formula>(5);
	}
	
	public FormulaGrounder(AtomManager m, ResultList res) {
		this(m,res,null);
	}
	
	public Formula ground(Formula f) {
		reset();
		FormulaTraverser.traverse(f, this);
		return pop();
	}
	
	public boolean hasNext() {
		return position<results.size();
	}
	
	public void next() {
		position++;
		reset();
	}
	
	public GroundTerm getResultVariable(Variable var) {
		return results.get(position, var);
	}
	
	public void push(Formula f) {
		stack.add(f);
	}
	
	public Formula pop() {
		if (stack.isEmpty()) throw new ArrayIndexOutOfBoundsException();
		return stack.remove(stack.size()-1);
	}
	
	public void reset() {
		if (!stack.isEmpty()) throw new IllegalStateException("Element on stack!");
	}
	
	@Override
	public void afterConjunction(int noFormulas) {
		Formula[] f = new Formula[noFormulas];
		for (int i=0;i<noFormulas;i++) f[i]=pop();
		push(new Conjunction(f));
	}

	@Override
	public void afterDisjunction(int noFormulas) {
		Formula[] f = new Formula[noFormulas];
		for (int i=0;i<noFormulas;i++) f[i]=pop();
		push(new Disjunction(f));
	}

	@Override
	public void afterNegation() {
		push(new Negation(pop()));
	}

	
	@Override
	public void visitAtom(Atom atom) {
		GroundTerm[] args = new GroundTerm[atom.getPredicate().getArity()];
		Term[] atomArgs = atom.getArguments();
		assert args.length==atomArgs.length;
		for (int i=0;i<args.length;i++) {
			if (atomArgs[i] instanceof Variable) {
				Variable v = (Variable)atomArgs[i];
				if (varAssign!=null && varAssign.hasVariable(v)) {
					args[i]=varAssign.getVariable(v);
				} else {
					args[i] = results.get(position, v);
				}
				
			} else {
				assert atomArgs[i] instanceof GroundTerm;
				args[i] = (GroundTerm)atomArgs[i];
			}
		}
		push(atommanger.getAtom(atom.getPredicate(), args));
	}
	
	public static List<Formula> ground(Formula f, AtomEventFramework store, ResultList results) {
		List<Formula> formulas = new ArrayList<Formula>(results.size());
		FormulaGrounder grounder = new FormulaGrounder(store, results);
		while (grounder.hasNext()) {
			FormulaTraverser.traverse(f, grounder);
			formulas.add(grounder.pop());
			grounder.next();
		}
		return formulas;		
	}
		
}
