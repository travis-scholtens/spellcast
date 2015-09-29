var Game = {
    display: null,
    map: {},
    engine: null,
    player: null,
    ananas: null,
    messages: [],
    rack: [],
    rack_size: 6,
    rackTileLocations: {},
    mode: [],

    values: {
      a: 1,
      b: 3,
      c: 3,
      d: 2,
      e: 1,
      f: 4,
      g: 2,
      h: 4,
      i: 1,
      j: 8,
      k: 5,
      l: 1,
      m: 3,
      n: 1,
      o: 1,
      p: 3,
      q: 10,
      r: 1,
      s: 1,
      t: 1,
      u: 1,
      v: 4,
      w: 4,
      x: 8,
      y: 4,
      z: 10
    },
    
    frequencies: {
      a: 9,
      b: 2,
      c: 2,
      d: 4,
      e: 12,
      f: 2,
      g: 3,
      h: 2,
      i: 9,
      j: 1,
      k: 1,
      l: 4,
      m: 2,
      n: 6,
      o: 8,
      p: 2,
      q: 1,
      r: 6,
      s: 4,
      t: 6,
      u: 4,
      v: 2,
      w: 2,
      x: 1,
      y: 2,
      z: 1
    },

    init: function() {
        this.display = new ROT.Display({width: 26, height: 26, fontSize: 24, spacing:1});
        var canvas = this.display.getContainer();
        canvas.style.width = '100%';
        canvas.style.height = '100%';
        document.getElementById("main").appendChild(canvas);
        
        this._generateMap();
        
        this.scheduler = new ROT.Scheduler.Simple();
        this.scheduler.add(this.player, true);
        for (var i = 0; i < this.foes.length; i++)
          this.scheduler.add(this.foes[i], true);

        this.engine = new ROT.Engine(this.scheduler);
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

        var rooms = digger.getRooms();
        var upRoom = ROT.RNG.getUniformInt(0, rooms.length-1);
        var downRoom = upRoom;
        while (downRoom == upRoom)
          downRoom = ROT.RNG.getUniformInt(0, rooms.length-1);

        var upCenter = rooms[upRoom].getCenter(),
            downCenter = rooms[downRoom].getCenter();
        this.map[(upCenter[0] - 1) + ',' + upCenter[1]].glyph = '\u261d';
        this.map[downCenter[0] + ',' + downCenter[1]].glyph = '\u261f';

        this.player = this._createBeing(Player, freeCells);
        this.player._x = upCenter[0] + 1;
        this.player._y = upCenter[1];

        this.foes = [];
        for (var i = 0; i < ROT.RNG.getUniformInt(12, 18); i++)
          this.foes.push(this._createBeing(Pedro, freeCells));

        this.revealFrom({x: this.player._x, y: this.player._y});
        this._drawWholeMap();
    },

    modal: function(size, button) {
      var display = this.display.getOptions();
      var xmax = size.x,
          ymax = size.y;
      var origin = {
          x: (display.width - xmax)/2,
          y: (display.height - ymax)/2 };
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
          this.display.draw(origin.x + x,
                            origin.y + y,
                            glyph);
        }
      if (button !== undefined) {
        for (x = 0; x < button.length + 2; x++)
          for (y = 0; y < 3; y++) {
            glyph = x == 0 ? (y == 0 ? '\u256d' :
                              y == 2 ? '\u2570' :
                              '\u2502') :
                    x == button.length + 1 ? (y == 0 ? '\u256e' :
                                              y == 2 ? '\u256f' :
                                              '\u2502') :
                    y == 0 || y == 2 ? '\u2500' :
                    '';
            this.display.draw(origin.x + xmax/2 - button.length/2 + x - 1,
                              origin.y + ymax - 4 + y,
                              glyph);
          }
        this.display.drawText(origin.x + xmax/2 - button.length/2,
                              origin.y + ymax - 3,
                              button);
      }
      return {
          x: origin.x + xmax/2,
          y: origin.y + ymax/2 };
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
        var being = new what(x, y);
        this.map[key].contents = being;
        return being;
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
      var messageCount = this.messages.length;
      for (var i = 0; i < messageCount; i++)
        this.display.drawText(0, i, '> ' + this.messages[i]);
      this.messages.length = 0;
      for (var dx = 0; dx < display.width; dx++)
        for (var dy = messageCount; dy < display.height - 3; dy++) {
          var x = this.player.getX() - display.width/2 + dx,
              y = this.player.getY() - display.height/2 + dy;
          if ((0 <= x && x < this.width) &&
              (0 <= y && y < this.height)) {
            if (x == this.player._x && y == this.player._y)
              this.display.draw(dx, dy, '@', '#ff0');
            else {
              var tile = this.map[x + ',' + y];
              var glyph = '', color = 'white';
              if (tile != undefined && tile.revealed) {
                if (tile.contents != null) {
                  glyph = tile.contents.glyph;
                  color = tile.contents.color;
                } else if (tile.corpse != null) {
                  glyph = tile.corpse;
                  color = 'darkgray';
                } else
                  glyph = tile.glyph;
              }
              this.display.draw(dx, dy, glyph, color);
            }
          }
        }
      this.drawRackTiles();
    },

    drawRackTiles: function() {
      var display = this.display.getOptions();
      var right = 20;
      for (var dx = 0; dx < right; dx++) {
        var glyph = dx == 0 ? '\u2514' :
                    dx == right - 1 ? '\u2518' :
                    '\u2500';
        this.display.draw(dx, display.height - 1, glyph, "tan");
      }
      this.rackTileLocations.length = 0;
      for (var i = 0; i < this.rack_size; i++)
        this.clearRackSpace(this.display, 2 + 3 * i, display.height - 2);
      for (var i = 0; i < this.rack.length; i++) {
        this.drawRackTile(
            this.display, 2 + 3 * i, display.height - 2, this.rack[i], 'tan');
        for (var j = 0; j < 2; j++)
          this.rackTileLocations[2 + 3 * i + j] = i;
      }

      var left = 21;
      for (var dx = left; dx < display.width; dx++) {
        var glyph = dx == left ? '\u2514' :
                    dx == display.width - 1 ? '\u2518' :
                    '\u2500';
        this.display.draw(dx, display.height - 1, glyph, "gray");
      }

      this.clearRackSpace(this.display, 23, display.height - 2);
      var playerKey = this.player._x + ',' + this.player._y;
      if (this.map[playerKey].corpse != null) {
        this.drawRackTile(
            this.display, 23, display.height - 2, this.map[playerKey].corpse, 'gray');
        for (var j = 0; j < 2; j++)
          this.rackTileLocations[23 + j] = 6;
      }
    },

    clearRackSpace: function(display, x, y) {
      for (var i = 0; i < 3; i++)
        this.display.draw(x - 1 + i, y, '');
    },

    rackTileText: function(glyph, color) {
      return '%b{' + color + '}%c{black}' + glyph;
    },

    drawRackTile: function(display, x, y, glyph, color) {
      var value = this.values[glyph],
          ax,
          digits;
      if (value < 10) {
        ax = x - 0.5;
        digits = [value];
      } else {
        ax = x - 0.75;
        digits = [Math.floor(value/10), value % 10];
      }
      display.drawText(ax, y, this.rackTileText(glyph, color));
      for (var i = 0; i < digits.length; i++)
        display.drawText(ax + 0.75 * i, y,
            this.rackTileText(String.fromCodePoint(0x2080 + digits[i]), color));
    },

    message: function(text) {
      this.messages.push(text);
    }
};

var Tile = function(glyph) {
  this.glyph = glyph;
  this.revealed = false;
  this.contents = null;
  this.corpse = null;
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
    window.addEventListener("mouseup", this);
    window.addEventListener("touchstart", this);
}

Player.prototype.attack = function(foe) {
  var damage = Math.min(
      Math.max(0, Math.floor(ROT.RNG.getNormal(5, 2))),
      foe.health);
  foe.health -= damage;
  return damage;
}

dropCorpse = function(map, x, y, corpse) {
  var q = [[x,y]];
  var seen = {};
  while (q.length > 0) {
    var u = q.shift();
    var key = u[0] + ',' + u[1];
    if (map[key].corpse == null) {
      map[key].corpse = corpse;
      return;
    }
    seen[key] = true;
    for (var dx = -1; dx <= 1; dx++)
      for (var dy = -1; dy <= 1; dy++)
        if (dx != 0 || dy != 0) {
          var nx = u[0] + dx, ny = u[1] + dy;
          var nkey = nx + ',' + ny;
          if (nkey in map && !(nkey in seen))
            q.push([nx, ny]);
        }
  }
}
    
Player.prototype.keyPress = function(code) {
    if (Game.mode.length > 0) return;

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

    var contents = Game.map[newKey].contents;
    if (contents != null) {
      var damage = this.attack(contents);
      if (contents.health == 0) {
        Game.map[newKey].contents = null;
        dropCorpse(Game.map, newX, newY, contents.glyph);
        Game.scheduler.remove(contents);
        Game.message('You kill the ant!');
      }
      else if (damage > 0)
        Game.message('You hit the ant! %c{red}(-' + damage + ')');
      else
        Game.message('You miss the ant.');
    }
    else {
      this._x = newX;
      this._y = newY;
      var key = this._x + ',' + this._y;
      if (Game.map[key].corpse != null && Game.rack.length < Game.rack_size) {
        Game.rack.push(Game.map[key].corpse);
        Game.map[key].corpse = null;
        Game.message('You take the ant corpse.');
      }
      Game.revealFrom({x: this._x, y: this._y});
    }
    window.removeEventListener("keydown", this);
    window.removeEventListener("mouseup", this);
    window.removeEventListener("touchstart", this);
    Game.engine.unlock();
}

Player.prototype.clickRackTile = function(index) {
  var center,
      glyph = null,
      color,
      button = undefined,
      width = 8,
      height = 4;
  if (index < Game.rack.length) {
    var remaining = Game.rack.splice(index);
    glyph = remaining.shift();
    Game.rack = Game.rack.concat(remaining);
    color = 'tan';
    button = 'Drop';
    width = 12;
    height = 8;
  } else if (index == 6) {
    var key = Game.player._x + ',' + Game.player._y
    glyph = Game.map[key].corpse;
    height = 4;
    if (glyph != null) {
      Game.map[key].corpse = null;
      color = 'gray';
    }
  }
  if (glyph != null) {
    center = Game.modal({x: width, y: height}, button);
    Game.drawRackTile(Game.display, center.x - (width-8)/4, center.y - height/4, glyph, color);
    Game.drawRackTiles();
    Game.mode.push(true);
  }
}

Player.prototype.click = function(loc) {
  if (Game.mode.length == 0 && loc.y == 24) {
    var rackTileIndex = Game.rackTileLocations[loc.x];
    if (rackTileIndex !== undefined)
      this.clickRackTile(rackTileIndex);
  }
}

ROT.Display.prototype.eventToClientPosition = function(e) {
  if (e.touches) {
    var x = e.touches[0].clientX;
    var y = e.touches[0].clientY;
  } else {
    var x = e.clientX;
    var y = e.clientY;
  }

  var rect = this._context.canvas.getBoundingClientRect();
  x -= rect.left;
  y -= rect.top;

  x *= this._context.canvas.width / this._context.canvas.clientWidth;
  y *= this._context.canvas.height / this._context.canvas.clientHeight;

  if (x < 0 || y < 0 || x >= this._context.canvas.width || y >= this._context.canvas.height)
    return [-1, -1];

  return this._backend.eventToPosition(x, y);
}

Player.prototype.handleEvent = function(e) {
  if (e.type == 'keydown')
    this.keyPress(e.keyCode);
  else {
    var coords = Game.display.eventToClientPosition(e);
    if (coords[0] >= 0 && coords[1] >= 0)
      this.click({x: coords[0], y: coords[1]});
  }
  if (Game.mode.length == 0)
    Game._drawWholeMap();
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
    this.glyph = 'a';
    this.health = 10;
    this.color = 'red';
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
    if (path.length > 0) {
        x = path[0][0];
        y = path[0][1];
        if (x == Game.player._x && y == Game.player._y)
          Game.message('The ant hits you!');
        else {
          if (Game.map[x + ',' + y].contents != null)
            return;
          Game.map[this._x + ',' + this._y].contents = null;
          this._x = x;
          this._y = y;
          Game.map[this._x + ',' + this._y].contents = this;
        }
    }
}
