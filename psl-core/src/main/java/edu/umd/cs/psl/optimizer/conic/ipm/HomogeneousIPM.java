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
package edu.umd.cs.psl.optimizer.conic.ipm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cern.colt.matrix.tdouble.DoubleMatrix1D;
import cern.colt.matrix.tdouble.DoubleMatrix2D;
import cern.colt.matrix.tdouble.algo.DenseDoubleAlgebra;
import cern.colt.matrix.tdouble.algo.decomposition.SparseDoubleCholeskyDecomposition;
import cern.colt.matrix.tdouble.impl.DiagonalDoubleMatrix2D;
import cern.colt.matrix.tdouble.impl.SparseDoubleMatrix2D;
import cern.jet.math.tdouble.DoubleFunctions;
import edu.umd.cs.psl.config.ConfigBundle;
import edu.umd.cs.psl.config.ConfigManager;
import edu.umd.cs.psl.optimizer.conic.ConicProgramSolver;
import edu.umd.cs.psl.optimizer.conic.program.Cone;
import edu.umd.cs.psl.optimizer.conic.program.ConicProgram;
import edu.umd.cs.psl.optimizer.conic.program.NonNegativeOrthantCone;
import edu.umd.cs.psl.optimizer.conic.program.SecondOrderCone;
import edu.umd.cs.psl.optimizer.conic.program.Variable;

/**
 * Primal-dual interior-point method using the homogeneous model.
 * 
 * This solver follows the algorithm presented in
 * E. D. Andersen, C. Roos and T. Terlaky. "On implementing a primal-dual
 * interior-point method for conic quadratic optimization."
 * <i>Math. Programming</i> 95(2), February 2003.
 * 
 * @author Stephen Bach <bach@cs.umd.edu>
 *
 */
public class HomogeneousIPM implements ConicProgramSolver {
	
	private static final Logger log = LoggerFactory.getLogger(HomogeneousIPM.class);
	
	/**
	 * Prefix of property keys used by this class.
	 * 
	 * @see ConfigManager
	 */
	public static final String CONFIG_PREFIX = "hipm";
	
	/**
	 * Key for double property. The IPM will iterate until the duality gap
	 * is less than its value.
	 */
	public static final String DUALITY_GAP_RED_THRESHOLD_KEY = CONFIG_PREFIX + ".dualitygapredthreshold";
	/** Default value for DUALITY_GAP_THRESHOLD_KEY property. */
	public static final double DUALITY_GAP_RED_THRESHOLD_DEFAULT = 0.01;
	
	/**
	 * Key for double property. The IPM will iterate until the primal and dual infeasibilites
	 * are each less than its value.
	 */
	public static final String INFEASIBILITY_RED_THRESHOLD_KEY = CONFIG_PREFIX + ".infeasibilityredthreshold";
	/** Default value for INFEASIBILITY_THRESHOLD_KEY property. */
	public static final double INFEASIBILITY_RED_THRESHOLD_DEFAULT = 10e-8;
	
	/**
	 * Key for double property. The IPM will iterate until the primal and dual infeasibilites
	 * are each less than its value.
	 */
	public static final String SIG_THRESHOLD_KEY = CONFIG_PREFIX + ".sigthreshold";
	/** Default value for INFEASIBILITY_THRESHOLD_KEY property. */
	public static final double SIG_THRESHOLD_DEFAULT = 10e-8;
	
	/**
	 * Key for double property. The IPM will iterate until the primal and dual infeasibilites
	 * are each less than its value.
	 */
	public static final String TAU_THRESHOLD_KEY = CONFIG_PREFIX + ".tauthreshold";
	/** Default value for INFEASIBILITY_THRESHOLD_KEY property. */
	public static final double TAU_THRESHOLD_DEFAULT = 10e-8;
	
	/**
	 * Key for double property. The IPM will iterate until the primal and dual infeasibilites
	 * are each less than its value.
	 */
	public static final String MU_THRESHOLD_KEY = CONFIG_PREFIX + ".muthreshold";
	/** Default value for INFEASIBILITY_THRESHOLD_KEY property. */
	public static final double MU_THRESHOLD_DEFAULT = 10e-8;

	private double dualityGapRedThreshold;
	private double infeasibilityRedThreshold;
	private double sigThreshold;
	private double tauThreshold;
	private double muThreshold;
	
	private double gamma = 0.8;
	
	private int stepNum;
	
	public HomogeneousIPM(ConfigBundle config) {
		dualityGapRedThreshold = config.getDouble(DUALITY_GAP_RED_THRESHOLD_KEY, DUALITY_GAP_RED_THRESHOLD_DEFAULT);
		infeasibilityRedThreshold = config.getDouble(INFEASIBILITY_RED_THRESHOLD_KEY, INFEASIBILITY_RED_THRESHOLD_DEFAULT);
		sigThreshold = config.getDouble(SIG_THRESHOLD_KEY, SIG_THRESHOLD_DEFAULT);
		tauThreshold = config.getDouble(TAU_THRESHOLD_KEY, TAU_THRESHOLD_DEFAULT);
		muThreshold = config.getDouble(MU_THRESHOLD_KEY, MU_THRESHOLD_DEFAULT);
	}

	@Override
	public Double solve(ConicProgram program) {
		program.checkOutMatrices();

		double mu;
		DoubleMatrix2D A = program.getA();
		
		log.debug("Starting optimzation with {} variables and {} constraints.", A.columns(), A.rows());
		
		mu = doSolve(program);
		
		log.debug("Optimum found.");
		
		program.checkInMatrices();
		
		return mu;
	}
	
	private double doSolve(ConicProgram program) {
		DoubleMatrix2D A;
		DoubleMatrix1D x, b, w, s, c;
	
		A = program.getA();
		x = program.getX();
		b = program.getB();
		w = program.getW();
		s = program.getS();
		c = program.getC();
		
		DenseDoubleAlgebra alg = new DenseDoubleAlgebra();
		
		double cDotX, bDotW;
		double gap, mu, primalInfeasibility, dualInfeasibility, sig;
		boolean primalFeasible, dualFeasible, isSig, gapIsReduced, tauIsSmall, muIsSmall; 
		boolean solved, programInfeasible, illPosed;
		
		/* Initializes program matrices that can be reused for entire procedure */
		HIPMProgramMatrices pm = getProgramMatrices(program);
		
		/* Initializes special variables for the homogeneous model */
		HIPMVars vars = new HIPMVars();
		vars.tau = 1;
		vars.kappa = 1;
		
		/* Computes values for stopping criteria */
		double muZero = (x.zDotProduct(s) + vars.tau * vars.kappa) / program.getV();
		double primalInfRedDenom = Math.max(1.0, alg.norm2(A.zMult(x, null).assign(
				b.copy().assign(DoubleFunctions.mult(vars.tau))
				, DoubleFunctions.minus)));
		double dualInfRedDenom = Math.max(1.0, alg.norm2(A.zMult(x, null).assign(
				b.copy().assign(DoubleFunctions.mult(vars.tau))
				, DoubleFunctions.minus)));
		double gapRedDenom = Math.max(1.0, alg.norm2(A.zMult(x, null).assign(
				b.copy().assign(DoubleFunctions.mult(vars.tau))
				, DoubleFunctions.minus)));
		
		stepNum = 0;
		do {
			//program.trimUnrestrictedVariablePairs();
			step(program, pm, vars);
			
			mu		= (x.zDotProduct(s) + vars.tau * vars.kappa) / program.getV();
			cDotX	= x.zDotProduct(x);
			bDotW	= b.zDotProduct(w);
			
			primalInfeasibility = alg.norm2(
					A.zMult(x, b.copy().assign(DoubleFunctions.mult(vars.tau)), 1.0, -1.0, false)
					) / primalInfRedDenom;
			
			dualInfeasibility = alg.norm2(
					A.zMult(w, c.copy().assign(DoubleFunctions.mult(vars.tau)), 1.0, -1.0, true)
					.assign(s, DoubleFunctions.plus)
					) / dualInfRedDenom;
			
			gap = Math.abs(bDotW - cDotX - vars.kappa) / gapRedDenom;
			sig = Math.abs(cDotX / vars.tau - bDotW / vars.tau) / (1 + Math.abs(bDotW / vars.tau));
			
			log.trace("Itr: {} -- Gap: {} -- P. Inf: {} -- D. Inf: {} -- Obj: {}", new Object[] {++stepNum, mu, primalInfeasibility, dualInfeasibility, cDotX});
			
			primalFeasible	= primalInfeasibility <= infeasibilityRedThreshold;
			dualFeasible	= dualInfeasibility <= infeasibilityRedThreshold;
			isSig			= sig <= sigThreshold;
			gapIsReduced	= gap <= dualityGapRedThreshold;
			tauIsSmall		= vars.tau <= tauThreshold * Math.max(1.0, vars.kappa);
			muIsSmall		= mu <= muThreshold * muZero;
			
			solved				= primalFeasible && dualFeasible && isSig;
			programInfeasible	= primalFeasible && dualFeasible && gapIsReduced && tauIsSmall;
			illPosed			= muIsSmall && tauIsSmall;
		} while (!solved && !programInfeasible && !illPosed);
		
		if (illPosed)
			throw new IllegalArgumentException("Optimization program is ill-posed.");
		else if (programInfeasible)
			throw new IllegalArgumentException("Optimization program is infeasible.");
		else {
			x.assign(DoubleFunctions.div(vars.tau));
			w.assign(DoubleFunctions.div(vars.tau));
			s.assign(DoubleFunctions.div(vars.tau));
		}
		
		return mu;
	}

	private void step(ConicProgram program, HIPMProgramMatrices pm, HIPMVars vars) {
		HIPMIntermediates im = getIntermediates(program, pm, vars);
		HIPMResiduals res = getResiduals(program, pm, vars, im, gamma);
		HIPMSearchDirection sd = getSearchDirection(program, pm, vars, res, im);
		double stepSize = getStepSize(program, vars, sd);
		
		program.getX().assign(sd.dx.assign(DoubleFunctions.mult(stepSize)), DoubleFunctions.plus);
		program.getW().assign(sd.dw.assign(DoubleFunctions.mult(stepSize)), DoubleFunctions.plus);
		program.getS().assign(sd.ds.assign(DoubleFunctions.mult(stepSize)), DoubleFunctions.plus);
		vars.tau += sd.dTau * stepSize;
		vars.kappa += sd.dKappa * stepSize;
	}
	
	private HIPMProgramMatrices getProgramMatrices(ConicProgram program) {
		HIPMProgramMatrices pm = new HIPMProgramMatrices();
		
		SparseDoubleMatrix2D A = program.getA();
		int size = A.columns();
		
		pm.T	= new SparseDoubleMatrix2D(size, size);
		pm.invT	= new SparseDoubleMatrix2D(size, size);
		pm.W	= new SparseDoubleMatrix2D(size, size);
		pm.invW	= new SparseDoubleMatrix2D(size, size);
		
		// TODO: Watch out for new cone types. Should throw exception if they exist. 
		for (NonNegativeOrthantCone cone : program.getNonNegativeOrthantCones()) {
			int i = program.index(cone.getVariable());
			pm.T.setQuick(i, i, 1.0);
			pm.invT.setQuick(i, i, 1.0);
			pm.W.setQuick(i, i, 1.0);
			pm.invW.setQuick(i, i, 1.0);
		}
		
		for (SecondOrderCone cone : program.getSecondOrderCones()) {
			for (Variable var : cone.getVariables()) {
				int i = program.index(var);
				pm.T.setQuick(i, i, 1.0);
				pm.invT.setQuick(i, i, 1.0);
				pm.W.setQuick(i, i, -1.0);
				pm.invW.setQuick(i, i, -1.0);
			}
			int i = program.index(cone.getNthVariable());
			pm.W.setQuick(i, i, 1.0);
			pm.invW.setQuick(i, i, 1.0);
		}
		
		return pm;
	}
	
	private HIPMIntermediates getIntermediates(ConicProgram program
			, HIPMProgramMatrices pm, HIPMVars vars
			) {
		HIPMIntermediates im = new HIPMIntermediates();
		DenseDoubleAlgebra alg = new DenseDoubleAlgebra();
		
		SparseDoubleMatrix2D A = program.getA();
		DoubleMatrix1D x = program.getX();
		DoubleMatrix1D b = program.getB();
		DoubleMatrix1D c = program.getC();
		
		im.Theta = getTheta(program);
		im.invTheta = getInvTheta(program);
		
		DoubleMatrix1D xBar = alg.mult(im.Theta, alg.mult(pm.W, x));
		DoubleMatrix1D sBar = alg.mult(im.invTheta, alg.mult(pm.invW, x));
		im.XBar = new DiagonalDoubleMatrix2D((int) xBar.size(), (int) xBar.size(), 0);
		im.invXBar = new DiagonalDoubleMatrix2D((int) xBar.size(), (int) xBar.size(), 0);
		im.SBar = new DiagonalDoubleMatrix2D((int) sBar.size(), (int) sBar.size(), 0);
		
		for (int i = 0; i < xBar.size(); i++) {
			im.XBar.setQuick(i, i, xBar.getQuick(i));
			im.invXBar.setQuick(i, i, 1 / xBar.getQuick(i));
			im.SBar.setQuick(i, i, sBar.getQuick(i));
		}
		
		/* Computes intermediate matrices */
		im.AThetaSqWSq = (SparseDoubleMatrix2D) A.zMult(
				im.Theta.zMult(
						im.Theta.zMult(
								pm.W.zMult(pm.W, null)
								, null)
						, null)
				, null);
		
		im.invThetaSqInvWSq = (SparseDoubleMatrix2D) im.invTheta.zMult(
				im.invTheta.zMult(
						pm.invW.zMult(pm.invW, null)
						, null)
				, null);
		
		/* Computes M and finds its Cholesky factorization */
		SparseDoubleMatrix2D M = (SparseDoubleMatrix2D) A.zMult(
				im.invThetaSqInvWSq.zMult(A, null, 1.0, 0.0, false, true)
				, null);
		im.M = new SparseDoubleCholeskyDecomposition(M.getColumnCompressed(false), 1);
		
		/* Computes intermediate vectors */
		
		/* g2 */
		im.g2 = b.copy();
		im.g2.assign(im.AThetaSqWSq.zMult(c, null), DoubleFunctions.plus);
		im.M.solve(im.g2);
		
		/* g1 */
		im.g1 = im.invThetaSqInvWSq.zMult(
				c.copy().assign(A.zMult(im.g2, null, 1.0, 0.0, true), DoubleFunctions.minus)
				, null).assign(DoubleFunctions.mult(-1.0));
		
		return im;
	}
	
	private HIPMResiduals getResiduals(ConicProgram program
			, HIPMProgramMatrices pm, HIPMVars vars, HIPMIntermediates im
			, double gamma
			) {
		HIPMResiduals res = new HIPMResiduals();
		
		return res;
	}
	
	private HIPMSearchDirection getSearchDirection(ConicProgram program
			, HIPMProgramMatrices pm, HIPMVars vars, HIPMResiduals res
			, HIPMIntermediates im
			) {
		HIPMSearchDirection sd = new HIPMSearchDirection();
		SparseDoubleMatrix2D A = program.getA();
		DoubleMatrix1D b = program.getB();
		DoubleMatrix1D c = program.getC();
		
		/* h2 */
		DoubleMatrix1D h2 = res.r1.copy().assign(
				im.AThetaSqWSq.zMult(res.r2.copy().assign(
						im.invTheta.zMult(pm.invW.zMult(
								pm.invT.zMult(im.invXBar.zMult(res.r4, null), null)
								, null), null)
						, DoubleFunctions.minus), null)
				, DoubleFunctions.plus);
		im.M.solve(h2);
		
		/* h1 */
		DoubleMatrix1D h1 = im.invThetaSqInvWSq.zMult(
				res.r2.copy().assign(
						im.Theta.zMult(pm.W.zMult(im.invXBar.zMult(res.r4, null), null), null)
						, DoubleFunctions.minus)
				.assign(
						A.zMult(h2, null, 1.0, 0.0, true)
						, DoubleFunctions.minus)
				, null);
	
		/* Computes search direction */
		sd.dTau = (res.r3 - c.zDotProduct(h1) + b.zDotProduct(h2))
				/ ((vars.kappa/vars.tau) + c.zDotProduct(im.g1) - b.zDotProduct(im.g2));
		sd.dx = h1.copy().assign(DoubleFunctions.mult(sd.dTau)).assign(im.g1, DoubleFunctions.plus);
		sd.dw = h2.copy().assign(DoubleFunctions.mult(sd.dTau)).assign(im.g2, DoubleFunctions.plus);
		sd.dKappa = (res.r5 - vars.kappa*sd.dTau) / vars.tau;
		sd.ds = im.Theta.zMult(pm.W.zMult(pm.T.zMult(im.invXBar.zMult(
				res.r4.copy().assign(
						im.SBar.zMult(pm.T.zMult(im.Theta.zMult(pm.W.zMult(sd.dx, null), null), null), null)
						, DoubleFunctions.minus)
				, null), null), null), null);
		
		return sd;
	}
	
	private double getStepSize(ConicProgram program
			, HIPMVars vars, HIPMSearchDirection sd) {
		double alphaMax = 1.0;
		
		DoubleMatrix1D x = program.getX();
		DoubleMatrix1D s = program.getS();
		
		/* Checks distance to boundaries of cones */
		for (Cone cone : program.getCones()) {
			alphaMax = Math.min(cone.getMaxStep(program.getVarMap(), x, sd.dx), alphaMax);
			alphaMax = Math.min(cone.getMaxStep(program.getVarMap(), s, sd.ds), alphaMax);
		}
		
		/* Checks distance to min. tau */
		if (sd.dTau < 0)
			alphaMax = Math.min(-1 * vars.tau / sd.dTau, alphaMax);
		
		/* Checks distance to min. kappa */
		if (sd.dKappa < 0)
			alphaMax = Math.min(-1 * vars.kappa / sd.dKappa, alphaMax);
		
		return alphaMax;
	}
	
	private SparseDoubleMatrix2D getTheta(ConicProgram program) {
		return null;
	}
	
	private SparseDoubleMatrix2D getInvTheta(ConicProgram program) {
		return null;
	}
	
	private class HIPMProgramMatrices {
		private SparseDoubleMatrix2D T;
		private SparseDoubleMatrix2D invT;
		private SparseDoubleMatrix2D W;
		private SparseDoubleMatrix2D invW;
	}
	
	private class HIPMVars {
		private double tau;
		private double kappa;
	}
	
	private class HIPMIntermediates {
		private DiagonalDoubleMatrix2D XBar;
		private DiagonalDoubleMatrix2D invXBar;
		private DiagonalDoubleMatrix2D SBar;
		private SparseDoubleMatrix2D Theta;
		private SparseDoubleMatrix2D invTheta;
		private SparseDoubleMatrix2D AThetaSqWSq;
		private SparseDoubleMatrix2D invThetaSqInvWSq;
		private SparseDoubleCholeskyDecomposition M;
		private DoubleMatrix1D g1;
		private DoubleMatrix1D g2;
	}
	
	private class HIPMResiduals {
		private DoubleMatrix1D r1;
		private DoubleMatrix1D r2;
		private double r3;
		private DoubleMatrix1D r4;
		private double r5;
	}
	
	private class HIPMSearchDirection {
		private DoubleMatrix1D dx;
		private DoubleMatrix1D dw;
		private DoubleMatrix1D ds;
		private double dTau;
		private double dKappa;
	}
}
