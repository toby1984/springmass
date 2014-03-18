package de.codesourcery.springmass.springmass;

import java.util.Random;

import com.badlogic.gdx.math.Vector3;

public class WindSimulator {

	private WindParameters params;
	
	private final Vector3 currentDirection=new Vector3(); 	
	private final Vector3 desiredDirection=new Vector3();
	
	private float currentForce;
	private float desiredForce;
	
	private final Vector3 directionIncrement=new Vector3();
	private float forceIncrement=0;
	
	private int stepCount;
	private Random random; 
	
	public WindSimulator(Random random,WindParameters params) {
		this.params = params;
		this.random = random;
		generateNewDirectionAndForce();
	}
	
	public void set(WindSimulator other) 
	{
		this.params = other.params;
		this.currentDirection.set( other.currentDirection );
		this.desiredDirection.set( other.desiredDirection );
		this.currentForce = other.currentForce;
		this.desiredForce = other.desiredForce;
		
		this.directionIncrement.set( other.directionIncrement );
		this.forceIncrement = other.forceIncrement;
		
		this.stepCount = other.stepCount;
		this.random = other.random;
	}
	
	public void step() 
	{
		stepCount++;
		if ( params.isEnabled() ) 
		{
			if ( (stepCount % params.getStepsUntilDirectionChanged()) == 0 ) 
			{
				generateNewDirectionAndForce();
			} else {
				stepDirectionAndForce();
			}
		}
	}
	
	private void stepDirectionAndForce() 
	{
		if ( Math.abs( desiredForce - currentForce ) > 0.01 ) {
			currentForce += forceIncrement;
			// System.out.println( stepCount+" : stepped force to "+currentForce);
		}
		if ( Math.abs( currentDirection.dst2( desiredDirection ) ) > 0.01*0.01 ) {
			currentDirection.add( directionIncrement );
			// System.out.println( stepCount+" : stepped  direction to "+currentDirection);
		}
	}

	private void generateNewDirectionAndForce() 
	{
		if ( ! params.isEnabled() ) 
		{
			directionIncrement.set(0,0,0);
			forceIncrement=0;
			desiredForce=currentForce=0;
			currentDirection.set(0,0,0);
			desiredDirection.set(0,0,0);
			System.out.println("*** ["+stepCount+"] wind: DISABLED");
			return;
		}
		
		// generate new random force
		desiredForce = params.getMinForce()+random.nextFloat()*( params.getMaxForce() - params.getMinForce() );
		forceIncrement = (desiredForce - currentForce)/(float) params.getStepsUntilDirectionAdjusted(); 
		
		// System.out.println("*** ["+stepCount+"] wind: current_force="+currentForce+", new_force="+desiredForce+" , increment="+forceIncrement+" ( "+params.getStepsUntilDirectionAdjusted()+" steps)");
		
		// generate new random direction
		
		final float minXZAngleInRad = SphericalCoordinates.getMinXZAngleInRad( params.getMinAngle() , params.getMaxAngle() );
		final float maxXZAngleInRad = SphericalCoordinates.getMaxXZAngleInRad( params.getMinAngle() , params.getMaxAngle() );
		
		final float minXYAngleInRad = SphericalCoordinates.getMinXYAngleInRad( params.getMinAngle() , params.getMaxAngle() );
		final float maxXYAngleInRad = SphericalCoordinates.getMaxXYAngleInRad( params.getMinAngle() , params.getMaxAngle() );		
		
		final float newXZAngleInRad = minXZAngleInRad+random.nextFloat()*(maxXZAngleInRad-minXZAngleInRad);
		final float newXYAngleInRad = minXYAngleInRad+random.nextFloat()*(maxXYAngleInRad-minXYAngleInRad);		
		
		desiredDirection.set( new SphericalCoordinates( newXZAngleInRad , newXYAngleInRad ).toUnitVector() );
		
		directionIncrement.set( desiredDirection );
		directionIncrement.sub( currentDirection );
		
		// TODO: Might look even nicer if we don't interpolate linearly but use spherical coordinates here as well...
		directionIncrement.scl( 1.0f / params.getStepsUntilDirectionAdjusted() );
		
		// System.out.println("*** ["+stepCount+"] wind: current_direction="+currentDirection+", new_direction="+desiredDirection+" , increment="+directionIncrement+" ( "+params.getStepsUntilDirectionAdjusted()+" steps)");
	}
	
	public void getCurrentWindVector(Vector3 v) {
		v.set( currentDirection );
		v.nor();
		v.scl( currentForce );
	}	
}