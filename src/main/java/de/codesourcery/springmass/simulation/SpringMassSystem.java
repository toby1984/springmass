/**
 * Copyright 2012 Tobias Gierke <tobias.gierke@code-sourcery.de>
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
package de.codesourcery.springmass.simulation;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import com.badlogic.gdx.math.Vector3;

import de.codesourcery.springmass.math.VectorUtils;

public final class SpringMassSystem 
{
    private final ReentrantLock lock = new ReentrantLock();	
    private final ThreadPoolExecutor threadPool;

    protected final Mass[][] massArray;

    public final List<Spring> springs = new ArrayList<>();

    private final List<Spring> removedSprings = new ArrayList<>();
    
    private SimulationParameters params;

    private SpringMassSystem copiedFrom;
    
    // mapping ORIGINAL mass -> copied mass
    private IdentityHashMap<Mass,Mass> massMappings;
    
    private Random random;
    private final WindSimulator windSimulator;
    
    private final int forkJoinBatchCount;

    protected abstract class ParallelTaskCreator<T> 
    {
        public abstract Runnable createTask(Iterable<T> chunk,CountDownLatch taskFinishedLatch);
    }    
    
    protected interface GridIterator extends Iterator<Mass> 
    {
    	/**
    	 * Returns the mass right of the element returned by the last call to {@link #next()}. 
    	 * @return mass or <code>null</code> if either <code>next()</code> has never been called
    	 * or the element returned by <code>next()</code> was the right-most element in a row
    	 */
    	public Mass rightNeighbour();
    	
    	/**
    	 * Returns the mass below the element returned by the last call to {@link #next()}. 
    	 * @return mass or <code>null</code> if either <code>next()</code> has never been called
    	 * or the element returned by <code>next()</code> is in the last row of a slice.
    	 */    	
    	public Mass bottomNeighbour();
    }
    
	/**
	 * Iterable view of a rectangular slice of the mass array.
	 * 
	 * <p>Used to distribute work across multiple CPU cores.</p>
	 * 
	 * @author tobias.gierke@code-sourcery.de
	 */
    public final class Slice implements Iterable<Mass> {
    	
    	private final Mass[][] array = massArray;
    	
    	public final int xStart;
    	public final int yStart;
    	
    	public final int xEnd;
    	public final int yEnd;
    	
    	private final boolean fetchNeighbours;
    	
    	private final boolean isAtRightEdge; // used to determine whether it's safe to access the first column of the slice to the right of us 
    	private final boolean isAtBottomEdge; // used to determine whether it's safe to access the first row of the slice to the below us
    	
    	/**
    	 * 
    	 * @param x0
    	 * @param y0
    	 * @param width
    	 * @param height
    	 * @param fetchNeighbours whether to also fetch the right and bottom neighbour of every element being traversed
    	 */
    	public Slice(int x0,int y0,int width,int height,boolean fetchNeighbours,boolean isAtRightEdge,boolean isAtBottomEdge) {
    		this.xStart = x0;
    		this.yStart = y0;
    		this.xEnd = x0 + width;
    		this.yEnd = y0 + height;
    		this.fetchNeighbours = fetchNeighbours;
    		this.isAtRightEdge = isAtRightEdge;
    		this.isAtBottomEdge=isAtBottomEdge;
    	}
    	
    	@Override
    	public String toString() {
    		return "Slice[ ("+xStart+","+yStart+")->("+xEnd+","+yEnd+") , fetchNeighbours="+fetchNeighbours+" ]";
    	}
    	
    	public Iterator<Mass> iterator() 
    	{
    		if ( fetchNeighbours) 
    		{
        		return new GridIterator() 
        		{
        	    	private int x = xStart;
        	    	private int y = yStart;
        	    	
        	    	private Mass rightNeighbour;
        	    	private Mass bottomNeighbour;
        	    	
    				@Override
    				public boolean hasNext() 
    				{
    					return x < xEnd && y < yEnd;
    				}

    				@Override
    				public Mass next() 
    				{
    					final Mass result = array[x][y];
    					if ( isAtRightEdge ) {
    						rightNeighbour  = (x+1) < xEnd ? array[x+1][y] : null;
    					} else {
    						rightNeighbour  = array[x+1][y]; // TODO: hackish, another thread my manipulate this Mass instance concurrently...
    					}
    					
    					if ( isAtBottomEdge ) {
    						bottomNeighbour = (y+1) < yEnd ? array[x][y+1] : null;
    					} else {
    						bottomNeighbour = array[x][y+1];
    					}
    					
    					x++;
    					if ( x >= xEnd) {
    						x = xStart;
    						y++;
    					} 
    					return result;
    				}

    				@Override
    				public void remove() { throw new UnsupportedOperationException(); }

    				@Override
    				public Mass rightNeighbour() { return rightNeighbour; }

    				@Override
    				public Mass bottomNeighbour() { return bottomNeighbour; }
        		};    			
    		}
    		return new GridIterator() 
    		{
    	    	private int x = xStart;
    	    	private int y = yStart;
    	    	
				@Override
				public boolean hasNext() 
				{
					return x < xEnd && y < yEnd;
				}

				@Override
				public Mass next() 
				{
					final Mass result = array[x++][y];
					if ( x >= xEnd) {
						x = xStart;
						y++;
					} 
					return result;
				}

				@Override
				public void remove() { throw new UnsupportedOperationException(); }

				@Override
				public Mass rightNeighbour() { throw new UnsupportedOperationException("Wrong iterator type"); }

				@Override
				public Mass bottomNeighbour() { throw new UnsupportedOperationException("Wrong iterator type"); }
    		};
    	}
    }
    
    public SpringMassSystem createCopy() 
    {
        lock();
        try 
        {
            final Mass[][] arrayCopy = new Mass[ params.getGridColumnCount() ][];
            for ( int x = 0 ; x < params.getGridColumnCount() ; x++ ) 
            {
                arrayCopy[x] = new Mass[ params.getGridRowCount() ];
            }

            // clone particles
            final IdentityHashMap<Mass,Mass> massMappings = new IdentityHashMap<>();
            for ( int x = 0 ; x < params.getGridColumnCount() ; x++ ) 
            {
                for ( int y = 0 ; y < params.getGridRowCount() ; y++ ) 
                {        
                    final Mass mOrig = massArray[x][y];
                    final Mass mCopy = mOrig.createCopyWithoutSprings();
                    arrayCopy[x][y]=mCopy;
                    massMappings.put( mOrig , mCopy );
                }
            }

            final SpringMassSystem copy = new SpringMassSystem( this.params , arrayCopy , random  );
            copy.windSimulator.set( this.windSimulator );
            copy.massMappings = massMappings;
            copy.copiedFrom = this;

            // add springs
            for ( Spring spring : springs ) 
            {
                Mass copy1 = massMappings.get( spring.m1 );
                Mass copy2 = massMappings.get( spring.m2 );
                copy.addSpring( spring.createCopy( copy1 , copy2 ) );
            }
            
            return copy;
        } 
        finally {
            unlock();
        }
    }

    public void updateFromOriginal() 
    {
        lock();
        try 
        {
            final List<Spring> removed;            
            copiedFrom.lock();
            try 
            {
                // copy positions and flags
                for ( int x = 0 ; x < params.getGridColumnCount() ; x++ ) 
                {
                    for ( int y = 0 ; y < params.getGridRowCount() ; y++ ) 
                    {
                        final Mass original = copiedFrom.massArray[x][y];                
                        final Mass clone = this.massArray[x][y];
                        clone.copyPositionAndFlagsFrom( original );
                    }
                }  
                
                // get all springs that were removed since the last call
                // to updateFromOriginal() and immediately clear the list
                // so we don't process them again
                removed = new ArrayList<>( copiedFrom.removedSprings );
                copiedFrom.removedSprings.clear();                
            } 
            finally {
                copiedFrom.unlock();
            }
            
            // remove all springs that were removed from the original            
            for ( Spring removedSpring : removed ) 
            {
                Mass m1 = massMappings.get( removedSpring.m1 );
                Mass m2 = massMappings.get( removedSpring.m2 );
                final Spring copy = removedSpring.createCopy( m1 ,m2 );
                copy.remove();
                springs.remove( copy );
            }
        } finally {
            unlock();
        }
    }

    public SpringMassSystem(SimulationParameters params,Mass[][] massArray,Random random) 
    {
    	final int cpuCount = Runtime.getRuntime().availableProcessors();
    	this.forkJoinBatchCount = (int) Math.ceil( cpuCount * params.getForkJoinLoadFactor() );
    	System.out.println("Will process "+forkJoinBatchCount+" batches in parallel (cpu core count: "+cpuCount+", load factor: "+params.getForkJoinLoadFactor()+" )");
    	
    	this.random = random;
        this.params = params;
        
        this.windSimulator = new WindSimulator(random, params.getWindParameters() );
        
        this.massArray = massArray;
        
        final BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(this.forkJoinBatchCount * 2 );
        final ThreadFactory threadFactory = new ThreadFactory() {

            @Override
            public Thread newThread(Runnable r)
            {
                final Thread t = new Thread(r,"calculation-thread");
                t.setDaemon(true);
                return t;
            }
        };
        threadPool = new ThreadPoolExecutor(cpuCount, cpuCount, 60, TimeUnit.SECONDS, workQueue, threadFactory, new ThreadPoolExecutor.CallerRunsPolicy() );
    }

    public void destroy() throws InterruptedException 
    {
        lock();
        try 
        {
            threadPool.shutdown();
            threadPool.awaitTermination(60,TimeUnit.SECONDS );
        } 
        finally 
        {
            unlock();
        }
    }

    public Mass[][] getMassArray() 
    {
        return massArray;
    }

    public Mass getNearestMass(Vector3 pos,double maxDistanceSquared) {

        Mass best = null;
        double closestDistance = Double.MAX_VALUE;
        for ( int y = 0 ; y < params.getGridRowCount() ; y++ ) 
        {
        	for ( int x = 0 ; x < params.getGridColumnCount() ; x++ ) {
        		final Mass m = massArray[x][y];
                double distance = m.squaredDistanceTo( pos ); 
                if ( best == null || distance < closestDistance ) 
                {
                    best = m;
                    closestDistance = distance; 
                }        		
        	}
        }
        return closestDistance > maxDistanceSquared ? null : best;
    }

    public List<Spring> getSprings() {
        return springs;
    }

    public void addSpring(Spring s) {
        s.m1.addSpring( s );
        springs.add( s );
    }

    public void lock() 
    {
        try 
        {
            if ( ! lock.tryLock( 5 , TimeUnit.SECONDS ) ) 
            {
                throw new RuntimeException("Thread "+Thread.currentThread().getName()+" failed to aquire lock after waiting 5 seconds");
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void unlock() {
        lock.unlock();
    }

    public void step() 
    {
        final Vector3 gravity = new Vector3(0,-1,0).scl(params.getGravity());
        final Vector3 zeroGravity = new Vector3(0,0,0);
        
        lock();
        try 
        {
        	windSimulator.step();
        	
        	final boolean springsCanBreak = params.getMaxSpringLength() > 0;
            for ( int count = params.getIterationCount() ; count > 0 ; count--) 
            {
                // solve constraints
                solveConstraints();

                // remove springs exceeding the max. length
                if ( springsCanBreak ) {
                	removeBrokenSprings(springs);
                }

                // apply spring forces to particles
                if ( count == 1 ) 
                {
                   	applyForces(gravity , params.getWindParameters().isEnabled() );
                } else {
                	applyForces( zeroGravity , false ); // only apply gravity once
                }
            }      
            
            calculateNormals();
        } 
        finally {
            unlock();
        }
    }
    
    private void calculateNormals() 
    {
    	final ParallelTaskCreator<Mass> taskCreator = new ParallelTaskCreator<Mass>() {

			@Override
			public Runnable createTask(final Iterable<Mass> chunk,final CountDownLatch taskFinishedLatch) 
			{
				return new Runnable() {

					@Override
					public void run() 
					{
						try {
							calculateNormals( (Slice) chunk );
						} 
						finally {
							taskFinishedLatch.countDown();
						}
					}
				};
			}
    		
    	};
		forEachParallel(massArray, taskCreator , false );
	}

	private void calculateNormals(Slice slice) 
    {
    	final int xEnd = slice.xEnd;
    	final int yEnd = slice.yEnd;
    	for ( int x = slice.xStart ; x < xEnd ; x++ ) 
    	{
    		for ( int y = slice.yStart ; y < yEnd ; y++ ) 
    		{
    			calculateAveragedNormal(x,y,xEnd,yEnd);
    		}
    	}
    }
    
	private void calculateAveragedNormal(int x,int y,int rowCount,int columnCount)
	{
		final Mass m = massArray[x][y];
		
		final Vector3 normal = m.normal;
		normal.set(0,0,0);
		
		final Vector3 position = m.currentPosition;
		
		final Vector3 v1 = new Vector3();
		final Vector3 v2 = new Vector3();
		
		int count = 0;
		
		if ( (x+1) < columnCount && (y+1) < rowCount ) {
			v1.set( massArray[x+1][y].currentPosition ).sub(position);
			v2.set( massArray[x][y+1].currentPosition ).sub(position);
			normal.add( v1.crs( v2 ) );
			count++;
		}
		
		if ( (x+1) < columnCount && (y-1) >= 0 ) {
			v1.set( massArray[x][y-1].currentPosition ).sub(position);			
			v2.set( massArray[x+1][y].currentPosition ).sub(position);
			normal.add( v1.crs( v2 ) );
			count++;
		}	
		
		if ( (x-1) >= 0 && (y-1) >= 0 ) {
			v1.set( massArray[x-1][y].currentPosition ).sub(position);
			v2.set( massArray[x][y-1].currentPosition ).sub(position);
			normal.add( v1.crs( v2 )  );
			count++;
		}	
		
		if ( (x-1) >= 0 && (y+1) < rowCount ) {
			v1.set( massArray[x][y+1].currentPosition ).sub(position);			
			v2.set( massArray[x-1][y].currentPosition ).sub(position);
			normal.add( v1.crs( v2 ) );
			count++;
		}		

		normal.scl( 1.0f / (float) count );
		normal.nor();
	}    
    
    private Vector3 calculateWindForce(Mass mass,Mass rightNeighbour,Mass bottomNeighbour, Vector3 normalizedWindForce,Vector3 windForce) 
    {
    	final Vector3 v1 = new Vector3( rightNeighbour.currentPosition );
    	v1.sub( mass.currentPosition );
    	
    	final Vector3 v2 = new Vector3( bottomNeighbour.currentPosition );
    	v2.sub( mass.currentPosition );    	
    	
    	// calculate vector perpendicular to plane
    	final Vector3 crossProduct = v1.crs( v2 );
    	crossProduct.nor();
    	
    	// calculate angle between wind direction and surface normal
        final float angle = Math.abs( normalizedWindForce.dot( crossProduct ) );
        
        // scale wind force by angle 
        final Vector3 sumForces = new Vector3( windForce );
        sumForces.scl( angle );
        return sumForces; 
	}

	private void removeBrokenSprings(List<Spring> springs) 
    {
        double maxSpringLengthSquared = params.getMaxSpringLength();
        maxSpringLengthSquared *= maxSpringLengthSquared;
        for ( Iterator<Spring> it = springs.iterator() ; it.hasNext() ; )
        {
            final Spring s = it.next();
            if ( s.lengthSquared() > maxSpringLengthSquared && !(s.m1.isSelected() || s.m2.isSelected() ) )
            {
                it.remove();
                s.remove();
                removedSprings.add( s );
            }
        }
    }
    
    private void solveConstraints() 
    {
        final ParallelTaskCreator<Spring> creator = new ParallelTaskCreator<Spring>() {

            @Override
            public Runnable createTask(final Iterable<Spring> chunk,final CountDownLatch taskFinishedLatch)
            {
                return new Runnable() {

                    @Override
                    public void run()
                    {
                        try 
                        {
                            for ( Spring s : chunk ) 
                            {
                                s.calcForce();
                            }
                        } 
                        finally 
                        {
                            taskFinishedLatch.countDown();
                        }
                    }
                };
            }
        };

        forEachParallel( springs,  creator );        
    }    

    private void applyForces(final Vector3 gravity,final boolean applyWindForces) 
    {
        final ParallelTaskCreator<Mass> creator = new ParallelTaskCreator<Mass>() {

            @Override
            public Runnable createTask(final Iterable<Mass> chunk,final CountDownLatch taskFinishedLatch)
            {
                return new Runnable() {

                    @Override
                    public void run()
                    {
                        try {
                            applyForces( chunk , gravity , applyWindForces );
                        } finally {
                            taskFinishedLatch.countDown();
                        }
                    }
                };
            }
        };

        forEachParallel( massArray ,  creator , applyWindForces );
    }

    private void applyForces(final Iterable<Mass> masses,final Vector3 gravity,final boolean applyWindForces) 
    {
        final float deltaTSquared = params.getIntegrationTimeStep();

        final Vector3 windForce = new Vector3();
        windSimulator.getCurrentWindVector( windForce );
        
        final Vector3 normalizedWindForce = new Vector3( windForce );
        normalizedWindForce.nor();
        
        final float minY = -params.getYResolution()*0.2f;
        final GridIterator it = (GridIterator) masses.iterator();
        while ( it.hasNext() )
        {
        	final Mass mass = it.next();
            if ( mass.hasFlags( Mass.FLAG_FIXED | Mass.FLAG_SELECTED ) ) {
                continue;
            }

            Vector3 sumForces = new Vector3();
            for ( Spring s : mass.springs ) 
            {
                if ( s.m1 == mass ) {
                    sumForces.add( s.force );
                } else {
                    sumForces.sub( s.force );
                }
            }
            
            if ( applyWindForces ) 
            {
            	final Mass rightNeighbour = it.rightNeighbour();
				final Mass bottomNeighbour = it.bottomNeighbour();
				if ( rightNeighbour != null & bottomNeighbour != null ) {
					sumForces.add( calculateWindForce(mass, rightNeighbour , bottomNeighbour , normalizedWindForce, windForce) );
				}
            }

            // apply gravity
            sumForces.add( gravity );

            final Vector3 tmp = new Vector3(mass.currentPosition);

            final Vector3 posDelta = new Vector3(mass.currentPosition).sub(mass.previousPosition);

            Vector3 dampening = new Vector3(posDelta).scl( params.getSpringDampening() );
            sumForces.sub( dampening );

            sumForces.scl( 1.0f / (mass.mass*deltaTSquared) );
            posDelta.add( sumForces );

            VectorUtils.clampMagnitudeInPlace( posDelta, params.getMaxParticleSpeed() );
            mass.currentPosition.add( posDelta );

            if ( mass.currentPosition.y < minY) {
                mass.currentPosition.y = minY;
            }
            mass.previousPosition = tmp;
        }
    }    

    private <T> void forEachParallel(List<T> data,ParallelTaskCreator<T> taskCreator) {

    	final int chunkSize = data.size() / forkJoinBatchCount;
        final List<List<T>> chunks = splitList( data , chunkSize );
        final CountDownLatch latch = new CountDownLatch(chunks.size());
        for ( List<T> chunk : chunks )
        {
            threadPool.submit( taskCreator.createTask( chunk , latch ) );
        }

        try {
        	latch.await();
        } catch(Exception e) {
        	e.printStackTrace();
        }
    }
    
    private void forEachParallel(Mass[][] data,ParallelTaskCreator<Mass> taskCreator,boolean iteratorNeedsNeighbours) 
    {
        final List<Slice> slices = splitArray( data , iteratorNeedsNeighbours );
        final CountDownLatch latch = new CountDownLatch( slices.size() );
        
        for ( Slice slice : slices )
        {
            threadPool.submit( taskCreator.createTask( slice , latch ) );
        }

        try {
        	latch.await();
        } catch(Exception e) {
        	e.printStackTrace();
        }
    }    
    
    private <T> List<Slice> splitArray(final Mass[][] data,boolean iteratorNeedsNeighbours) 
    {
    	int horizSize = (int) Math.ceil( Math.sqrt( forkJoinBatchCount) );
    	if ( horizSize < 1 ) {
    		horizSize = 1;
    	}
    	
    	final int vertSize = horizSize;
    	
    	final int horizSlices = params.getGridColumnCount() / horizSize;
    	final int vertSlices = params.getGridRowCount() / vertSize;
    	
    	final int horizRest = params.getGridColumnCount() - horizSlices*horizSize;
    	final int vertRest = params.getGridRowCount() - vertSlices*vertSize;
    			
    	final List<Slice> result = new ArrayList<>();
    	
    	final int xEnd = horizSlices*horizSize;
    	final int yEnd = vertSlices*vertSize;
    	
		int x,y;
    	for ( y = 0; y < yEnd ; y+= vertSize ) 
    	{    	
    		final boolean isAtBottomEdge = (y+vertSize) >= yEnd;
	    	for ( x = 0 ; x < xEnd ; x+= horizSize ) 
	    	{
	    		boolean isAtRightEdge = (x+horizRest) >= xEnd;
	    		result.add( new Slice( x , y , horizSize , vertSize , iteratorNeedsNeighbours , isAtRightEdge , isAtBottomEdge ) );
	    	}
	    	if ( horizRest > 0 ) {
	    		result.add( new Slice( x , y , horizRest , vertSize , iteratorNeedsNeighbours , true , isAtBottomEdge  ) );
	    	}
    	}
    	if ( vertRest > 0 ) 
    	{
	    	for ( x = 0 ; x < xEnd ; x+= horizSize ) 
	    	{
	    		boolean isAtRightEdge = (x+horizRest) >= xEnd;
	    		result.add( new Slice( x , y , horizSize , vertRest , iteratorNeedsNeighbours , isAtRightEdge , true ) );
	    	}    
	    	if ( horizRest > 0 ) {
	    		result.add( new Slice( x , y , horizRest , vertRest , iteratorNeedsNeighbours , true , true ) );
	    	}	    	
    	}
    	return result;
    }

    private <T> List<List<T>> splitList(final List<T> list,final int chunkSize) 
    {
        final List<List<T>> result = new ArrayList<List<T>>();
        final int listSize = list.size();

        for ( int currentIndex = 0 ; currentIndex < listSize ; currentIndex += chunkSize ) {
            int end=currentIndex+chunkSize;
            if ( end > listSize ) {
                end = listSize;
            }
            result.add( list.subList( currentIndex , end ) );
        }
        return result;
    }    
}