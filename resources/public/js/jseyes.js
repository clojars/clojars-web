/* jseyes.js

The classic Xeyes in JavaScript
Written by Pintér Gábor
Feuerwehrgasse 1/2/9
Purbach am Neusiedlersee
A-7083, Austria
Tel: +43 681 20821067, +36 20 4331532
Email: pinter.gabor@gmx.at
Web: http://www.pic18.eu

Revisions:
  V1.0  2001-10-14  Original release
  V1.1  2001-12-08  NS6.1
  V1.2  2001-12-17  More parameters
  V1.3  2002-08-14  Adjustable speed
  V1.31 2002-08-26  Improved adjustable speed
  V1.4  2010-11-26  IE7, IE8, absolute position
  V2.0  2014-03-13  Object oriented, IE9+


This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 3
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

http://www.gnu.org/copyleft/gpl.html

For commercial license, and for other professional
JavaScript and Java components please contact the author. */

// Mouse position
window.mouse= { clientX:0, clientY:0, pageX:0, pageY:0 };

// Capture mouse position
window.addEventListener('mousemove', function(e) {
	window.mouse.clientX= e.clientX;
	window.mouse.clientY= e.clientY;
	window.mouse.pageX= e.pageX;
	window.mouse.pageY= e.pageY;
});

// Construct with defaults
function jseyes() {
	
	// Defaults
	this.link= "";
	this.main= { img:"",
			w:150, h:150 };
	this.eye1= { img:"",
			w:21, h:29, x:46,  y:58, xr:7, yr:10 };
	this.eye2= { img:"",
			w:21, h:29, x:102, y:58, xr:7, yr:10 };
	this.deltat= 40; // ms
	this.follow= 100; // %
	
	// Private variables
	this.trail= { x:0, y:0 };

	// Create image
	this.write= function() {
		// Create image
		img=	"<div id='__jseyes__' style='position:relative; z-index:5; overflow:hidden; "+
			"width:"+this.main.w+"px; height:"+this.main.h+"px'>\n"+
		
				"<div id='__jseye1__' style='position:absolute; z-index:6; overflow:visible; "+
				"left:"+(this.eye1.x-this.eye1.w/2)+"px; top:"+(this.eye1.y-this.eye1.h/2)+"px; "+
				"width:"+this.eye1.w+"px; height:"+this.eye1.h+"px'>\n"+
				"<img src='"+this.eye1.img+"' "+
				"width='"+this.eye1.w+"px' height='"+this.eye1.h+"px' "+
				"onClick=\"location.href='"+this.link+"'\">\n"+
				"</div>\n"+
		
				"<div id='__jseye2__' style='position:absolute; z-index:6; overflow:visible; "+
				"left:"+(this.eye2.x-this.eye2.w/2)+"px; top:"+(this.eye2.y-this.eye2.h/2)+"px; "+
				"width:"+this.eye2.w+"px; height:"+this.eye2.h+"px'>\n"+
				"<img src='"+this.eye2.img+"' "+
				"width='"+this.eye2.w+"px' height='"+this.eye2.h+"px' "+
				"onClick=\"location.href='"+this.link+"'\">\n"+
				"</div>\n"+
		
				"<img src='"+this.main.img+"' "+
				"width='"+this.main.w+"px' height='"+this.main.h+"px' "+
				"onClick=\"location.href='"+this.link+"'\">\n"+
		
			"</div>\n";
		// Write image
		document.write(img);
		// Create references, and delete temporary ids
		this.main.o= document.getElementById('__jseyes__');
		this.main.o.id= null;
		this.eye1.o= document.getElementById('__jseye1__');
		this.eye1.o.id= null;
		this.eye2.o= document.getElementById('__jseye2__');
		this.eye2.o.id= null;
		// Animate
		var writer= this;
		setInterval(function() {
			// trail follows mouse position relative to document
			writer.trail.x+= (window.mouse.pageX-writer.trail.x)*writer.follow/100;
			writer.trail.y+= (window.mouse.pageY-writer.trail.y)*writer.follow/100;
			// Move eyes to look at trail
			if (writer.main.o && writer.eye1.o && writer.eye2.o && writer.main.o.style) {
				// Find true position relative to body
				var p= { o:writer.main.o, x:0, y:0 };
				do {
					p.x+= p.o.offsetLeft;
					p.y+= p.o.offsetTop;
					p.o= p.o.offsetParent;
				} while (p.o);
				// Move eye1
				var d= { x:0, y:0 };
				d.x= writer.trail.x-p.x-writer.eye1.x;
				d.y= writer.trail.y-p.y-writer.eye1.y;
				r= Math.sqrt(d.x*d.x/(writer.eye1.xr*writer.eye1.xr)+d.y*d.y/(writer.eye1.yr*writer.eye1.yr));
				if (r<1) r=1;
				writer.eye1.o.style.left= (d.x/r+writer.eye1.x-writer.eye1.w/2)+"px";
				writer.eye1.o.style.top=  (d.y/r+writer.eye1.y-writer.eye1.h/2)+"px";
				// Move eye2
				d.x= writer.trail.x-p.x-writer.eye2.x;
				d.y= writer.trail.y-p.y-writer.eye2.y;
				r= Math.sqrt(d.x*d.x/(writer.eye2.xr*writer.eye2.xr)+d.y*d.y/(writer.eye2.yr*writer.eye2.yr));
				if (r<1) r=1;
				writer.eye2.o.style.left= (d.x/r+writer.eye2.x-writer.eye2.w/2)+"px";
				writer.eye2.o.style.top=  (d.y/r+writer.eye2.y-writer.eye2.h/2)+"px";
			}
		}, writer.deltat);
	};
}
