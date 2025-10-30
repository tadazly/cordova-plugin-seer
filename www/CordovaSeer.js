var exec = require('cordova/exec');

module.exports = {
    execAsync(func, param) {
        var args = Array.prototype.slice.call(arguments);
        return new Promise(function(resolve, reject) {
            args.unshift('CordovaSeer');
            args.unshift(function(err) {
                reject(err);
            });
            args.unshift(function(result) {
                resolve(result);
            });
            exec.apply(this, args);
        });
    },

    isPrimaryAppIcon() {
        return this.execAsync('isPrimaryAppIcon');
    },

    setAppIcon(iconName) {
        return this.execAsync('setAppIcon', [iconName]);
    },

    getCurrentAppIconName() {
        return this.execAsync('getCurrentAppIconName');
    },
}