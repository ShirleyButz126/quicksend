import os

def start(data_dir, static_dir, port):
    os.environ['QUICKSEND_DATA_ROOT'] = data_dir
    os.environ['QUICKSEND_STATIC_DIR'] = static_dir
    os.environ['PORT'] = str(port)
    os.environ['HEADLESS'] = '1'
    os.environ['QUICKSEND_ANDROID'] = '1'

    import app as quicksend_app
    quicksend_app.GLOBAL_PORT = int(port)
    quicksend_app.app.run(
        host='0.0.0.0',
        port=int(port),
        debug=False,
        threaded=True,
        use_reloader=False,
    )
