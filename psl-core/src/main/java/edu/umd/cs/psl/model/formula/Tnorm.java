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
package edu.umd.cs.psl.model.formula;

/**
 * This enumeratio defines different t-norms proposed in the literature on fuzzy logic
 * together with their implementation of conjuction and disjuction.
 * 
 * For more information on these t-norms see: http://en.wikipedia.org/wiki/T-norm_fuzzy_logics
 * 
 * The negation operation is the same for all t-norms.
 * @author Matthias Broecheler
 *
 */

public enum Tnorm {

	LUKASIEWICZ {

		@Override
		public double conjunction(double x, double y) {
			return Math.max(x+y - 1.0, 0);
		}

		@Override
		public double disjunction(double x, double y) {
			return Math.min(x+y, 1.0);
		}

	
	},
	
	GOEDEL{
		@Override
		public double conjunction(double x, double y) {
			return Math.min(x, y);
		}

		@Override
		public double disjunction(double x, double y) {
			return Math.max(x,y);
		}
			
	}, 
	
	PRODUCT {
		@Override
		public double conjunction(double x, double y) {
			return x*y;
		}

		@Override
		public double disjunction(double x, double y) {
			return x+y-x*y;
		}

	};
	

	
	public double negation(double x) {
		return 1.0 - x;
	}
	
	public abstract double disjunction(double x, double y);
	
	public abstract double conjunction(double x, double y);
		
	
}
