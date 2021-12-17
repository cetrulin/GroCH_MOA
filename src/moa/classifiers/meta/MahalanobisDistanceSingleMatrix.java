package moa.classifiers.meta;

import weka.core.MatrixUtils;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import com.yahoo.labs.samoa.instances.Attribute;
import com.yahoo.labs.samoa.instances.Instance;
import com.yahoo.labs.samoa.instances.InstanceImpl;
import com.yahoo.labs.samoa.instances.Instances;
import com.yahoo.labs.samoa.instances.WekaToSamoaInstanceConverter;
import weka.core.converters.ArffLoader.ArffReader;

//For MahalanobisDistance class
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;

public class MahalanobisDistanceSingleMatrix {
    
    /**
        * [Mahalanobis distance]
        * <p>
        * :: The Mahalanobis distance (or statistical distance) is a distance on \( \mathbb{R}^n \)
        *  based on correlations between variables by which different patterns can be identified and analyzed.
        * <p>
        * [Brief Description]
        * <p>
        * The Mahalanobis distance gauges similarity of an unknown sample set to a known one. It differs from
        *  Euclidean distance in that it takes into account the correlations of the data set and is scale-invariant. 
        *  In other words, it is a multivariate effect size.
        * <p>
        * [Definition]
        * <p>
        
        The Mahalanobis distance between two vectors \( a, b \) is given by:
        <p>
        \( \|a-b\|_M = \sqrt{(detM)^{\frac{1}{n}}(a-b)M^{-1}(a-b)^T} \),
        * <p>
        * where \(M\) is a positive-definite matrix (usually, the covariance matrix of a finite set consisting of \(observation\) \(vectors\)).
        * <p>
        * [Reference]
        * <p>
        * Deza, Michel Marie, Deza, Elena :: [Encyclopedia of Distances] :: Springer |
        * 2009
        * <p>
        * | 17.2 :: Relatives of Euclidean distance :: P.303 |
        * <p>
        * @see <a href="http://en.wikipedia.org/wiki/Mahalanobis_distance">Wikipedia Article</a>
        * 
        * @author Adapted from Juan Francisco Quesada-Brizuela
        * @author Also adapted from https://github.com/gajduk/mahalanobis-for-weka/blob/master/MahalanobisDistance.java
        * @author Adapted by Andres L. Suarez-Cetrulo
        * 
        */
    
    public RealMatrix covarMatrix;
    
    // Only for subspaces (if selected)
    private Integer[] attributeSubset;
    private Instances subsetHeaders;
    private Attribute[] subsetAttributes;
    Instance auxInst;
    
    public MahalanobisDistanceSingleMatrix(String [] matrixDatasetsPath) {
        if(matrixDatasetsPath[0] != "-1") {
            Instances [] datasets = new Instances[matrixDatasetsPath.length]; 
            Instances mergedDataset = null;
            for (int i = 0; i < datasets.length; i++) datasets[i] = loadDataset(matrixDatasetsPath[i]);
            mergedDataset = (Instances) datasets[0].get(0).copy().dataset();
            for (int i = 0; i < datasets.length; i++)  {
                for (int j = 0; j < datasets[i].numInstances() ; j++)  {
                    mergedDataset.add(datasets[i].get(j));
                }
            } this.covarMatrix = new Array2DRowRealMatrix(MatrixUtils.calculateCovariance(getFeaturesAsMatrix(mergedDataset)));
            
        } else System.out.println("WARNING! NO DATASETS SPECIFIED TO INITIALIZE THE MAHALANOBIS MATRIX. "
                    + "THIS SHOULD CRASH SOON.");
    }
    

    public MahalanobisDistanceSingleMatrix(String[] matrixDatasetsPath, Integer[] topologyAttributeSubset) {
        // Constructor in presence of an attribute subset.
        this.attributeSubset = topologyAttributeSubset;
        if(matrixDatasetsPath[0] != "-1") {
            Instances [] datasets = new Instances[matrixDatasetsPath.length]; 
            Instances mergedDataset = null;
            for (int i = 0; i < datasets.length; i++) datasets[i] = loadDataset(matrixDatasetsPath[i]);
            
            mergedDataset = (Instances) datasets[0].get(0).copy().dataset();
            for (int i = 0; i < datasets.length; i++)  {
                for (int j = 0; j < datasets[i].numInstances() ; j++)  {
                    mergedDataset.add(applyAttributeSubset(datasets[i].get(j)));
                }
            } mergedDataset.setAttributes(this.subsetAttributes);
            mergedDataset.setClassIndex(mergedDataset.get(0).numAttributes() - 1);
            
            this.covarMatrix = new Array2DRowRealMatrix(MatrixUtils.calculateCovariance(getFeaturesAsMatrix(mergedDataset)));
            
        } else System.out.println("WARNING! NO DATASETS SPECIFIED TO INITIALIZE THE MAHALANOBIS MATRIX. "
                    + "THIS SHOULD CRASH SOON.");		}

    // duplicate code fragment? TODO: create an static method in a separate object
    private Instance applyAttributeSubset(Instance inst) { 
        // 1. Iterate through attributes according to the subset and modify instance
        double classValueAux = inst.classValue();
        Instances subSetHeadersAux = new Instances(inst.dataset());
        int [] v_pos = new int [(this.attributeSubset.length + 1)];
        Attribute [] v_att = new Attribute [(this.attributeSubset.length + 1)]; 
        double [] v_val = new double [(this.attributeSubset.length+ 1)];
        for (int j = 0; j <= this.attributeSubset.length; j++) {
            v_pos[j] = j;
            if (j < this.attributeSubset.length) {
                if(this.attributeSubset[j] == inst.classIndex()) {
                    System.out.println("ISSUE FOUND! Label included as part of the feature subset, "
                            + "and therefore this attribute is duplicated!");
                    v_val[j] = (Double) null;
                    v_att[j] = null;
                } else if (this.attributeSubset[j] > inst.classIndex())
                    System.out.println("ISSUE FOUND! Out of range in feature subset. "
                            + "At least one of the specified indexed attributes point to an  attribute that does not exist!");
                else {
                    v_val[j] = inst.value(this.attributeSubset[j]);  // we may need to change this to names..
                    v_att[j] = inst.attribute(this.attributeSubset[j]);	
                }
            } else {
                v_val[j] = inst.classValue();
                v_att[j] = inst.classAttribute();
            }
        } inst = new InstanceImpl(1, v_val, v_pos, v_pos.length); // @author: asuarez
        this.auxInst = inst.copy();

        // 2. Modify headers of dataset (TODO: do this only once and send the dataset each time an object is created, or/and to the CH once) 
        if (this.subsetHeaders == null) {
            this.subsetHeaders = subSetHeadersAux;
            this.subsetHeaders.setAttributes(v_att);  
            this.subsetHeaders.setClassIndex(v_att.length - 1); 
            this.subsetAttributes = v_att;
        }
        inst.setDataset(subsetHeaders);  // add this. to topologyDataset
        inst.setClassValue(inst.numAttributes() - 1, classValueAux);  
        return inst;
    }
    

    public double[][] getFeaturesAsMatrix(Instances a) {
        double[][] res = new double[a.numInstances()][a.numAttributes()];
        for ( int i = 0 ; i < res.length ; ++i ) {
            Instance ii = a.instance(i);
            for ( int k = 0 ; k < res[i].length ; ++k ) {
                res[i][k] = ii.value(k);
            }
        }
        return res;
    }
    
    protected Instances loadDataset(String pathToArff) {
        WekaToSamoaInstanceConverter conv = new WekaToSamoaInstanceConverter();
        BufferedReader reader;
        Instances data = null;
        try {
            reader = new BufferedReader(new FileReader(pathToArff));
            ArffReader arff = new ArffReader(reader);
            weka.core.Instances staticDataset = arff.getData();
            staticDataset.setClassIndex(staticDataset.numAttributes() - 1);
            data = conv.samoaInstances(arff.getData());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } return data;
    }
    
    /**
        * Received two instances and one matrix value and calculates the Mahalanobis distance.
        * @param v1 vector number 1.
        * @param v2 vector number 2.
        * @param m1 is a positive-definite matrix (usually, the covariance matrix of a finite set consisting of \(observation\) \(vectors\)).
        * @return the Mahalanobis distance.
        */
    public double distance(Instance v1, Instance v2, RealMatrix m1) {
            double det = Math.pow((new LUDecomposition(m1).getDeterminant()), 1/(v1.numAttributes()));
            double[] tempSub = new double[v1.numAttributes()];
            System.out.println("MAHALANOBIS");
            for(int i=0; i < v1.numAttributes(); i++){
                tempSub[i] = (v1.value(i)-v2.value(i));
            }
            double[] temp = new double[v1.numAttributes()];
            for(int i=0; i < temp.length; i++){
                temp[i] = tempSub[i]*det;
            }
            RealMatrix m2 = new Array2DRowRealMatrix(new double[][] {temp});
            RealMatrix m3 = m2.multiply(new LUDecomposition(m1).getSolver().getInverse());
            RealMatrix m4 = m3.multiply((new Array2DRowRealMatrix(new double[][] { temp })).transpose());
            return Math.sqrt(m4.getEntry(0, 0));
    }
    
    /** For cases that do not use the topology feature subset, as this is set in applyAttributeSubset. */
    public Instance getAuxInstance(){
    	return this.auxInst;
    }
    
    /** For cases that do not use the topology feature subset, as this is set in applyAttributeSubset. */
    public Instance setAuxInstance(Instance inst) {
    	return this.auxInst = inst.copy();	
    }
    
    public double distance(double [] v1, double [] v2) {
		Instance v1Instance = null;
		Instance v2Instance = null;
		v1Instance = (Instance) this.auxInst.copy();
		v2Instance = (Instance) this.auxInst.copy();
		for (int j = 0; j < v1.length; j++) v1Instance.setValue(j, v1[j]);	
		for (int j = 0; j < v2.length; j++) v2Instance.setValue(j, v2[j]);
		return distance(v1Instance, v2Instance);
    }
    
    public double distance(Instance v1, Instance v2) {
        // d (Mahalanobis) = [(xB � xA)T * C -1 * (xB � xA)]^0.5   (LUDecomposition replaces here the inverse)
        double det = Math.pow((new LUDecomposition(this.covarMatrix).getDeterminant()), 1/(v1.numAttributes()));
        double[] tempSub = new double[v1.numAttributes()];
        for(int i=0; i < v1.numAttributes(); i++){
            tempSub[i] = (v1.value(i)-v2.value(i));
//				System.out.println(i+": value v1 --> "+v1.value(i)+"   value v2:"+v2.value(i)); test
        }
        double[] temp = new double[v1.numAttributes()];
        for(int i=0; i < temp.length; i++){
            temp[i] = tempSub[i]*det;
        }
        RealMatrix m2 = new Array2DRowRealMatrix(new double[][] {temp});
        RealMatrix m3 = m2.multiply(new LUDecomposition(this.covarMatrix).getSolver().getInverse());
        RealMatrix m4 = m3.multiply((new Array2DRowRealMatrix(new double[][] {temp})).transpose());
        return Math.sqrt(m4.getEntry(0, 0));
    }
    
    public double distance(Instances a, Instances b) {
        double [] dist = new double[a.numInstances()];
        double totalDist = 0.0;
        for (int i = 0; i < a.numInstances(); i++) {
            // Record distance from each data point to the nearest prototype (Single-link)
            double minDist = Double.MAX_VALUE;
            for (int j = 0; j < b.numInstances(); j++) {
                double d = distance(a.get(i), b.get(j));
                if (j == 0 || d < minDist) minDist = d;
            } dist[i] = minDist;
        } 
        // Return average distance
        for (int i = 0; i < dist.length; i++)  totalDist += dist[i];
        return totalDist / dist.length;
    }
}