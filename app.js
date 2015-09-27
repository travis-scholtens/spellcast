var Game = {
    display: null,
    map: {},
    engine: null,
    player: null,
    pedro: null,
    ananas: null,
    
    init: function() {
        this.display = new ROT.Display({width: 26, height: 26, fontSize: 24, spacing:1});
        var canvas = this.display.getContainer();
        canvas.style.width = '100%';
        canvas.style.height = '100%';
        document.getElementById("main").appendChild(canvas);
        
        this._generateMap();
        
        var scheduler = new ROT.Scheduler.Simple();
        scheduler.add(this.player, true);
        scheduler.add(this.pedro, true);

        this.engine = new ROT.Engine(scheduler);
        this.engine.start();
    },
    
    _generateMap: function() {
        this.width = 128;
        this.height = 128;
        this.radius = Math.sqrt(this.width * this.width + this.height * this.height);
        var digger = new ROT.Map.Digger(this.width, this.height);
        var freeCells = [];
        
        var digCallback = function(x, y, value) {
            if (value) { return; }
            
            var key = x+","+y;
            this.map[key] = new Tile('.');
            freeCells.push(key);
        }
        digger.create(digCallback.bind(this));

        this.fov = new ROT.FOV.PreciseShadowcasting(function(x, y) {
          return (x + ',' + y) in this.map;
        }.bind(this));
        
        this._generateBoxes(freeCells);

        var upRoom = ROT.RNG.getUniformInt(0, digger.getRooms().length-1);
        var downRoom = upRoom;
        while (downRoom == upRoom)
          downRoom = ROT.RNG.getUniformInt(0, digger.getRooms().length-1);

        var upCenter = digger.getRooms()[upRoom].getCenter(),
            downCenter = digger.getRooms()[downRoom].getCenter();
        this.map[(upCenter[0] - 1) + ',' + upCenter[1]].glyph = '\u261d';
        this.map[downCenter[0] + ',' + downCenter[1]].glyph = '\u261f';

        this.player = this._createBeing(Player, freeCells);
        this.player._x = upCenter[0] + 1;
        this.player._y = upCenter[1];

        this.pedro = this._createBeing(Pedro, freeCells);
        this.revealFrom({x: this.player._x, y: this.player._y});
        this._drawWholeMap();
        this.modal({x: 16, y: 6});
    },

    modal: function(size) {
      var display = this.display.getOptions();
      var xmax = size.x,
          ymax = size.y;
      for (var x = 0; x < xmax; x++)
        for (var y = 0; y < ymax; y++) {
          var glyph = x == 0 ? (y == 0 ? '\u256d' :
                                y == 2 ? '\u251c' :
                                y == ymax - 1 ? '\u2570' :
                                '\u2502') :
                      x == 1 ? (y == 0 ? '\u2500' :
                                y == 1 ? '\u2573' :
                                y == 2 ? '\u2500' :
                                y == ymax - 1 ? '\u2500' :
                                '') :
                      x == 2 ? (y == 0 ? '\u252c' :
                                y == 1 ? '\u2502' :
                                y == 2 ? '\u2518' :
                                y == ymax - 1 ? '\u2500' :
                                '') :
                      x == xmax - 1 ? (y == 0 ? '\u256e':
                                       y == ymax - 1 ? '\u256f' :
                                       '\u2502') :
                      y == 0 || y == ymax  - 1 ? '\u2500' :
                      '';
          this.display.draw((display.width - xmax)/2 + x,
                            (display.height - ymax)/2 + y,
                            glyph);
        }
    },

    revealFrom: function(loc) {
      this.fov.compute(loc.x, loc.y, this.radius, function(x, y, _, _) {
        var tile = this.map[x + ',' + y];
        if (tile != undefined)
          tile.revealed = true;
      }.bind(this));
    },
    
    _createBeing: function(what, freeCells) {
        var index = Math.floor(ROT.RNG.getUniform() * freeCells.length);
        var key = freeCells.splice(index, 1)[0];
        var parts = key.split(",");
        var x = parseInt(parts[0]);
        var y = parseInt(parts[1]);
        return new what(x, y);
    },
    
    _generateBoxes: function(freeCells) {
        for (var i=0;i<10;i++) {
            var index = Math.floor(ROT.RNG.getUniform() * freeCells.length);
            var key = freeCells.splice(index, 1)[0];
            this.map[key].glyph = "*";
            if (!i) { this.ananas = key; } /* first box contains an ananas */
        }
    },
    
    _drawWholeMap: function() {
      var display = this.display.getOptions();
      for (var dx = 0; dx < display.width; dx++)
        for (var dy = 0; dy < display.height - 3; dy++) {
          var x = this.player.getX() - display.width/2 + dx,
              y = this.player.getY() - display.height/2 + dy;
          if ((0 <= x && x < this.width) &&
              (0 <= y && y < this.height)) {
            if (x == this.pedro._x && y == this.pedro._y)
              this.display.draw(dx, dy, "P", "red");
            else {
              var tile = this.map[x + ',' + y];
              var glyph = tile != undefined && tile.revealed ? tile.glyph : '';
              this.display.draw(dx, dy, glyph);
            }
          }
        }
      this.display.draw(display.width/2, display.height/2, "@", "#ff0");
      // bottom rack:
      //  _  _  _  _  _  _  _
      //
      // 0....5....1....1....2.
      //           0    5    0
      var right = 20;
      for (var dx = 0; dx < right; dx++) {
        var glyph = dx == 0 ? '\u2514' :
                    dx == right - 1 ? '\u2518' :
                    '\u2500';
        this.display.draw(dx, display.height - 1, glyph, "tan");
      }
      this.display.drawText(1.5, display.height - 2, '%b{tan}%c{black}c');
      this.display.drawText(1.5, display.height - 2, '%b{tan}%c{black}\u2083');
      this.display.drawText(4.5, display.height - 2, '%b{tan}%c{black}a');
      this.display.drawText(4.5, display.height - 2, '%b{tan}%c{black}\u2081');
      this.display.drawText(7.25, display.height - 2, '%b{tan}%c{black}z');
      this.display.drawText(7.25, display.height - 2, '%b{tan}%c{black}\u2081');
      this.display.drawText(8, display.height - 2, '%b{tan}%c{black}\u2080');
      this.display.drawText(10.5, display.height - 2, '%b{tan}%c{black}e');
      this.display.drawText(10.5, display.height - 2, '%b{tan}%c{black}\u2081');
      this.display.drawText(13.5, display.height - 2, '%b{tan}%c{black}o');
      this.display.drawText(13.5, display.height - 2, '%b{tan}%c{black}\u2081');
      this.display.drawText(16.25, display.height - 2, '%b{tan}%c{black}q');
      this.display.drawText(16.25, display.height - 2, '%b{tan}%c{black}\u2081');
      this.display.drawText(17, display.height - 2, '%b{tan}%c{black}\u2080');

      var left = 21;
      for (var dx = left; dx < display.width; dx++) {
        var glyph = dx == left ? '\u2514' :
                    dx == display.width - 1 ? '\u2518' :
                    '\u2500';
        this.display.draw(dx, display.height - 1, glyph, "gray");
      }
      this.display.drawText(22.25, display.height - 2, '%b{gray}%c{black}q');
      this.display.drawText(22.25, display.height - 2, '%b{gray}%c{black}\u2081');
      this.display.drawText(23, display.height - 2, '%b{gray}%c{black}\u2080');
    }
};

var Tile = function(glyph) {
  this.glyph = glyph;
  this.revealed = false;
  this.contents = null;
};

var Player = function(x, y) {
    this._x = x;
    this._y = y;
}
    
Player.prototype.getX = function() { return this._x; }
Player.prototype.getY = function() { return this._y; }

Player.prototype.act = function() {
    Game.engine.lock();
    window.addEventListener("keydown", this);
}
    
Player.prototype.handleEvent = function(e) {
    var code = e.keyCode;
    if (code == 13 || code == 32) {
        this._checkBox();
        return;
    }

    var keyMap = {};
    keyMap[38] = 0;
    keyMap[33] = 1;
    keyMap[39] = 2;
    keyMap[34] = 3;
    keyMap[40] = 4;
    keyMap[35] = 5;
    keyMap[37] = 6;
    keyMap[36] = 7;

    /* one of numpad directions? */
    if (!(code in keyMap)) { return; }

    /* is there a free space? */
    var dir = ROT.DIRS[8][keyMap[code]];
    var newX = this._x + dir[0];
    var newY = this._y + dir[1];
    var newKey = newX + "," + newY;
    if (!(newKey in Game.map)) { return; }

    this._x = newX;
    this._y = newY;
    Game.revealFrom({x: this._x, y: this._y});
    Game._drawWholeMap();
    window.removeEventListener("keydown", this);
    Game.engine.unlock();
}

Player.prototype._checkBox = function() {
    var key = this._x + "," + this._y;
    if (Game.map[key].glyph != "*") {
        alert("There is no box here!");
    } else if (key == Game.ananas) {
        alert("Hooray! You found an ananas and won this game.");
        Game.engine.lock();
        window.removeEventListener("keydown", this);
    } else {
        alert("This box is empty :-(");
    }
}
    
var Pedro = function(x, y) {
    this._x = x;
    this._y = y;
}
    
Pedro.prototype.act = function() {
    var x = Game.player.getX();
    var y = Game.player.getY();

    var passableCallback = function(x, y) {
        return (x+","+y in Game.map);
    }
    var astar = new ROT.Path.AStar(x, y, passableCallback, {topology:4});

    var path = [];
    var pathCallback = function(x, y) {
        path.push([x, y]);
    }
    astar.compute(this._x, this._y, pathCallback);

    path.shift();
    if (path.length == 1) {
        Game.engine.lock();
        alert("Game over - you were captured by Pedro!");
    } else {
        x = path[0][0];
        y = path[0][1];
        this._x = x;
        this._y = y;
        Game._drawWholeMap();
    }
}
