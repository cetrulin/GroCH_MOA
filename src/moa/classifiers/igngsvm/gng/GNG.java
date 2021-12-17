package moa.classifiers.igngsvm.gng;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.LinkedList;
import java.util.Queue;

import java.util.ArrayList;
import java.io.FileWriter;
import java.io.PrintWriter;
import com.yahoo.labs.samoa.instances.Instance;
import moa.classifiers.meta.MahalanobisDistanceSingleMatrix;
import moa.cluster.Clustering;
import moa.clusterers.AbstractClusterer;
import moa.core.Measurement;

import com.github.javacliparser.FlagOption;
import com.github.javacliparser.FloatOption;
import com.github.javacliparser.IntOption;


public class GNG extends AbstractClusterer implements Cloneable {

	private static final long serialVersionUID = -8566293434212159290L;

	public int id;
	private ArrayList<GUnit> S;	// List of quantizated units.
	private ArrayList<double[]> receivedPatterns;
	public IntOption lambdaOption = new IntOption("lambda", 'l', "Lambda", 100);
	public IntOption maxAgeOption = new IntOption("maxAge", 'm',"MaximumAge", 200);
	public FloatOption alfaOption = new FloatOption("alfa", 'a',"Alfa", 0.5);
	public FloatOption constantOption = new FloatOption("d", 'd',"d", 0.995);
	public FloatOption BepsilonOption = new FloatOption("epsilonB", 'e',"EpsilonB", 0.2);
	public FloatOption NepsilonOption = new FloatOption("epsilonN", 'n',"EpsilonN", 0.006);
	public IntOption stoppingCriteriaOption = new IntOption("stoppingCriteria", 'c', "Fixed Stopping criteria", 100);
	public FlagOption classAsAttributeOption = new FlagOption("classAsFeature", 'b',
			"Should the class be considered as a feature in the topology?");
	
	// Stopping criterias specific to trainUntilFulfilled()
	public IntOption percentualStoppingCriteriaOption = new IntOption("percentualStoppingCriteria", 'p', 
			"Stopping criteria as a compression rate of examples seen (-1 to disable it).", -1, -1, 100);
	public FloatOption quantizationErrAsStoppingCriteriaOption = new FloatOption("quantizationErrAsStoppingCriteria", 'q', 
			"The stopping criteria is the quantization error specified (-1 to disable it).", -1.0);
	public FloatOption quantizationErrDeltaAsStoppingCriteriaOption = new FloatOption("quantizationErrDeltaAsStoppingCriteria", 't', 
			"The algorithm will stop training when the quantization error does not change more than this threshold (Integer.MIN_VALUE to disable it). "
			+ "Add a negative number for only allowing the error to decrease", Integer.MIN_VALUE);
	public IntOption movingAverageSizeForDeltaStoppingOption = new IntOption("movingAverageSizeForDeltaStopping", 's',
			"This is the length of the moving average to track changes in the quantization error when using delta as an stopping criteria.", 10);
	
	private long instancesSeen;
	private int neuronsCreated;	
	
	public MahalanobisDistanceSingleMatrix distObj;
	public boolean useMahalanobisDistances;
	public Instance auxInst;
	
	public GNG() {
		this.id = (int) (Math.random() * 1000);
	}
	
//	public GNG (IntOption lambdaOption, FloatOption alfaOption, IntOption maxAgeOption, 
//			FloatOption constantOption, FloatOption BepsilonOption, FloatOption NepsilonOption,
//			FlagOption classAsAttributeOption) {
//		this.lambdaOption = lambdaOption;
//		this.alfaOption = alfaOption;
//		this.maxAgeOption = maxAgeOption;
//		this.constantOption = constantOption;
//		this.BepsilonOption = BepsilonOption;
//		this.NepsilonOption = NepsilonOption;
//		this.classAsAttributeOption = classAsAttributeOption;	
//		this.id = (int) (Math.random() * 1000);
////		this.stoppingCriteriaOption = stoppingCriteria  // TODO
////		this.percentualStoppingCriteriaOption = percentualStoppingCriteria;  // TODO
////		this.quantizationErrAsStoppingCriteriaOption = quantizationErrAsStoppingCriteria;  // TODO
//	}
	
	public ArrayList<GUnit> getS (){
		return S;	
	}

	public void log (String st){
	     try {
			PrintWriter pw = new PrintWriter(new FileWriter ("gng.log",true));		
			pw.println(st);
			pw.flush();
			pw.close ();
	     }
	     catch (Exception e){}
	}

	@Override
	public void resetLearningImpl() {
		this.S = new ArrayList<GUnit>();
		this.instancesSeen = 0;
		this.neuronsCreated = 0;
		this.receivedPatterns = new ArrayList<double[]>();
		
		// Ignoring fixed number for stopping criteria if another metric is set.
		if (this.percentualStoppingCriteriaOption.getValue() != -1 ||    
				this.quantizationErrAsStoppingCriteriaOption.getValue() != 1 )
			this.stoppingCriteriaOption.setValue(Integer.MAX_VALUE);
	}
	
	@Override
	public void resetLearning() {
		resetLearningImpl();
	}

	public int getNumberOfPrototypesCreated() {		
		return this.neuronsCreated;
	}
	
	public double getQuantizationError() {
		/** This method returns the quantization error (QE) of GNG.
		 * QE measures the average distance (mean error) of each sample of time series to its nearest quantization unit. */
		double sumErrors = 0.0;
		for (int i = 0; i < this.receivedPatterns.size(); i++) {
			double [] currentPattern = this.receivedPatterns.get(i);
			sumErrors += distance(currentPattern, getNearest(currentPattern)[0].w);  
		} return sumErrors/(double) this.S.size();		
	}
	
	
	/** Once this is called, GNG is fed the instances seen until reaching the stopping criteria. */
	public void trainUntilFulfilled() {
		assert this.receivedPatterns.size() > 0; // first test
		
		// Three different ways to stop (ordered by priority, only one is selected from the options supplied): 
		// 1. a targeted quantization error;
		// 2. a compression rate (percentage of prototypes to be created from the examples seen).
		// 3. a fixed number of prototypes to be created.
		double targetDelta = this.quantizationErrDeltaAsStoppingCriteriaOption.getValue();
		double targetError = this.quantizationErrAsStoppingCriteriaOption.getValue();
		
		if(targetDelta != Integer.MIN_VALUE){
			MovingAverage ma = new MovingAverage(this.movingAverageSizeForDeltaStoppingOption.getValue());
			ma.add(new BigDecimal(getQuantizationError(), MathContext.DECIMAL64));  // initialize moving average of quantization errors
			
			double qe = 0.0;
			double meanDelta = 0.0;
			for(int j = 0, counter = 0; 
					(meanDelta = ((qe - ma.getAverage().doubleValue()) / ma.getAverage().doubleValue())) < targetDelta || 
					counter < this.movingAverageSizeForDeltaStoppingOption.getValue(); 
					j++, counter++){      
				System.out.println( getNumberOfPrototypesCreated()+ " prototypes created, an error of " + qe + " and a mean delta of " + meanDelta + ".");
		        trainOnInstanceImpl(this.receivedPatterns.get(j));
		        qe = getQuantizationError();
		        ma.add(new BigDecimal(qe, MathContext.DECIMAL64));  // update quantization error
		        if(j + 1 == this.receivedPatterns.size()) j = -1;
		    }
		} else if (targetError != -1.0) {
			double qe;  // quantization error
			for(int j = 0; (qe = getQuantizationError()) < targetError; j++){      
				System.out.println( getNumberOfPrototypesCreated()+ " prototypes created and an error of " + qe + ".");
		        trainOnInstanceImpl(this.receivedPatterns.get(j));
		        if(j + 1 == this.receivedPatterns.size()) j = -1;
		    }
		// If not using Quantization Err as stopping criteria, then using a number of prototypes (as a compression rate or fixed number).	
		} else {
			double perc = this.percentualStoppingCriteriaOption.getValue();
			int maxPrototypes;
			
			// Translate stopping criteria to number of prototypes created
			if (perc != -1) maxPrototypes = (int) (((double) perc/100.0) * this.receivedPatterns.size()); // compresion rate
			else maxPrototypes = this.stoppingCriteriaOption.getValue(); // fixed number
			this.stoppingCriteriaOption.setValue(maxPrototypes);
			
			System.out.println("Train GNG until fulfilling the stopping criteria.");
			for(int j = 0; getNumberOfPrototypesCreated() < maxPrototypes; j++){      
				System.out.println( getNumberOfPrototypesCreated()+ " prototypes created of the " + 
									maxPrototypes + " cos there were " + this.receivedPatterns.size() + "received.");
		        trainOnInstanceImpl(this.receivedPatterns.get(j));
		        if(j + 1 == this.receivedPatterns.size()) j = -1;
		    }
		}
	}
	
	@Override
	public void trainOnInstance(Instance inst) {
		trainOnInstanceImpl(inst);
	}
	
	@Override
	public void trainOnInstanceImpl(Instance inst) {
		 // 1. Input distribution (class considered an attribute if the flag is set)
		trainOnInstanceImpl(parseInstanceToArray(inst));
	}

	private void trainOnInstanceImpl(double[] pattern) {
		this.instancesSeen++;
		// System.out.println("Patterns in GNG: "+instancesSeen);

		this.receivedPatterns.add(pattern);
		
		if(GUnit.getNumberOfGPrototypes(this.S) < 2){			
			// Saving only a single class = 0
			// GNG should deal with data distributions of each class separately (as independent GNG objects)
			GUnit p = new GUnit(pattern, 0);
			if(!this.S.contains(p)) S.add(p);
			
		} else if(this.neuronsCreated < this.stoppingCriteriaOption.getValue()){
						
			// 2. Finding s1 y s2		
			GUnit[] best = getNearest(pattern);
			GUnit s1 = best[0];
			GUnit s2 = best[1];			
			
			// 3-7: Update edges and local errors in s1 and move prototypes of s1 and neighbors, 
			// 3. Update the age of all edges in s1
			s1.updateAges(); 
			
			// 4. Update local error in s1	
			s1.setError(s1.getError() + distance(s1.w.clone(), pattern));	
			
			//5. Move s1 and its neighbors using EpsilonB & EpsilonN
			s1.updateW(this.BepsilonOption.getValue(), pattern);
			GUnit neighbors[] = s1.getNeighborhood();
			for (int j = 0; j < neighbors.length; j++) {
				neighbors[j].updateW(this.NepsilonOption.getValue(), pattern);
			}						
						
			// 6. Reset connections between s1 y s2 (add new edges and update current ones)
			GEdge c = s1.searchEdge(s2);
			if(c==null) s1.addNeighbor(s2);
			else c.age=0;
			
			// 7. Delete the edge when its age is greater than the max allowed and after, units without edges	
			s1.purgueGEdges(this.maxAgeOption.getValue());			
			for (int k = 0; k < S.size(); k++) {
				if(this.S.get(k).neighbors.size()==0){
					this.S.remove(k);
					--k;
				}
			}
			
			// 8. If the current iteration is a multiple of lambda, then there is an interpolation
			if(this.instancesSeen % this.lambdaOption.getValue() == 0 && this.instancesSeen > 0){
				GUnit q = computeQ(); // Compute the pattern with the greatest error
				GUnit f = computeneighborWithGreatestError(q); // Compute neighbor of q with most error
				GUnit r = interpolate(q, f); // Create the new neuron

				// Error of q y f decreases being multiplied by alfa.
				double aux = q.getError();
				aux *= this.alfaOption.getValue();
				q.setError(aux);
				aux = f.getError();
				aux *= this.alfaOption.getValue();
				f.setError(aux);
				
				// Error of r = new error of q
				r.setError(q.getError());
				this.S.add(r); // Update S
				this.neuronsCreated++;
			}
			
			// 9. Decrement error variables multiplying them by d
			for(int i = 0; i < S.size(); i++){
				GUnit current = S.get(i);
				double error = current.getError();
				error *= this.constantOption.getValue();
				current.setError(error);
			}
		}
	}

	public GUnit interpolate(GUnit q, GUnit f) {
		
		// Place r between q and the neighbor of f with the greatest error
		double wr[] = new double [q.w.length];				
		for (int i = 0; i < wr.length; i++) {
			wr[i] = 0.5 * (q.w[i] + f.w[i]);	
		}
		// Create new prototype (GUnit class is set as 0 as is indiferent to GNG)
		GUnit r = new GUnit(wr, 0);
		
		// Link r to q and f, and delete edge q-f
		r.addNeighbor(q);
		r.addNeighbor(f);
		q.removeNeighbor(f);
		return r;
	}

	public GUnit computeneighborWithGreatestError(GUnit q) {
		
		// Compute neighbor of q with the greatest error
		GUnit f;
		double error;
		double greatest = 0;
		int posGreatestErrorneighbor = 0;
		
		for (int i = 0; i < q.neighbors.size(); i++) {
			GEdge current = q.neighbors.get(i);
			error = 0;
			if(current.p0.equals(q)){
				error = current.p1.getError();
			} else {
				error = current.p0.getError();
			}
			if(error>greatest){
				greatest = error;
				posGreatestErrorneighbor = i;
			}
		}
		
		if(q.neighbors.get(posGreatestErrorneighbor).p0.equals(q)){
			f = q.neighbors.get(posGreatestErrorneighbor).p1;
			
		} else {
			f = q.neighbors.get(posGreatestErrorneighbor).p0;
			
		} return f;
	}
	
	/**
	 * This method determines the instance with the highest error.
	 * */
	public GUnit computeQ() {
		double greatest = 0;
		int posGreatest = 0;
		for(int i = 0; i < S.size(); i++){
			GUnit current = S.get(i);
			double error = current.getError();
			if(error > greatest){
				greatest = error;
				posGreatest = i;
			}
		}
		GUnit q = S.get(posGreatest);
		return q;
	}


	public GUnit[] getNearest(double pattern[]){
		GUnit first,second;
		GUnit p[] = new GUnit[2];
		
		if(S.size() >= 2){		
			if(distance(this.S.get(0).w, pattern) <= distance(this.S.get(1).w, pattern)){
				first = this.S.get(0);
				second = this.S.get(1);			
			} else {
				first = this.S.get(1);
				second = this.S.get(0);		
			} 
			
			for (int j = 2; j < this.S.size(); j++) {
				if(distance(this.S.get(j).w, pattern) < distance(first.w, pattern)){
					second = first;
					first = this.S.get(j);
				} else {
					if(distance(this.S.get(j).w, pattern) < distance(second.w, pattern))
						second = this.S.get(j);
				}	
			}
			
			p[0] = first;
			p[1] = second;
			
		}else{
			if(S.size() > 0){
				p[0] = this.S.get(0);
				p[1] = this.S.get(0);
			} else return null;
		}
		return p;
	}

	public double distance(double w1[], double w2[]){
		if (this.useMahalanobisDistances) {
			if (this.distObj.getAuxInstance() == null) this.distObj.setAuxInstance(this.auxInst);
			return this.distObj.distance(w1, w2);
		}
		else return GUnit.dist(w1, w2);
	}

	public void setMahalanobisDistObj(MahalanobisDistanceSingleMatrix m_dist) {
		this.distObj = m_dist;
		this.useMahalanobisDistances = true;
	}
	
	public void setAuxInstance(Instance inst) {
		this.auxInst = inst;
	}
	
	public double[] parseInstanceToArray(Instance inst) {
		// Adding class as an attribute depending if the flag is set
		double [] pattern =  new double[inst.numValues()-(this.classAsAttributeOption.isSet()? 0: 1)];
		for (int i = 0; i < pattern.length; i++) pattern[i] = inst.value(i);
		return pattern;
	}
	
	public void getModelDescription(StringBuilder out, int indent) {
        out.append("Growing Neural Gas for MOA, Implement by Andres Leon Suarez Cetrulo.");		
	}

	@Override
	/** Gets whether this learner needs a random seed.  */
	public boolean isRandomizable() {
		return false;
	}

	@Override
	public Clustering getClusteringResult() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public double[] getVotesForInstance(Instance inst) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	protected Measurement[] getModelMeasurementsImpl() {
		// TODO Auto-generated method stub
		return null;
	}
	
	public long getInstancesSeen () {
		return this.instancesSeen;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public GNG clone() {
	    try {
    		GNG cloned = (GNG) super.clone();
    		cloned.id = (int) (Math.random() * 1000);
    		cloned.S = (ArrayList<GUnit>) cloned.S.clone();
			return cloned;
		} catch (CloneNotSupportedException e) {
			e.printStackTrace();
		} return null;
	}


	public class MovingAverage {
	    private final Queue<BigDecimal> window = new LinkedList<BigDecimal>();
	    private final int period;
	    private BigDecimal sum = BigDecimal.ZERO;
	
	    public MovingAverage(int period) {
	        assert period > 0 : "Period must be a positive integer";
	        this.period = period;
	    }
	
	    public void add(BigDecimal num) {
	        this.sum = this.sum.add(num);
	        this.window.add(num);
	        if (this.window.size() > this.period) {
	            this.sum = this.sum.subtract(this.window.remove());
	        }
	    }
	
	    public BigDecimal getAverage() {
	        if (this.window.isEmpty()) return BigDecimal.ZERO; // technically the average is undefined
	        BigDecimal divisor = BigDecimal.valueOf(this.window.size());
	        return this.sum.divide(divisor, 2, RoundingMode.HALF_UP);
	    }
	}

}
