document.addEventListener('DOMContentLoaded', () => {
  // --- Header Scroll Effect ---
  const header = document.querySelector('header');
  if (header) {
    window.addEventListener('scroll', () => {
      if (window.scrollY > 50) {
        header.classList.add('scrolled');
      } else {
        header.classList.remove('scrolled');
      }
    });
  }

  // --- Starfield Background Animation ---
  const canvas = document.getElementById('starfield');
  if (canvas) {
    const ctx = canvas.getContext('2d');
    let stars = [];
    const maxStars = 150;
    let animationFrameId;

    function resizeCanvas() {
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
    }

    class Star {
      constructor() {
        this.reset(true);
      }

      reset(init = false) {
        this.x = Math.random() * canvas.width;
        this.y = init ? Math.random() * canvas.height : -10;
        this.size = Math.random() * 2 + 0.5;
        this.speed = Math.random() * 0.8 + 0.2;
        this.alpha = Math.random() * 0.7 + 0.3;
        if (this.size > 2) {
          this.speed *= 1.5;
          this.alpha = 1.0;
        }
        this.color = Math.random() > 0.7 
          ? (Math.random() > 0.5 ? '#ff6b9d' : '#a370f7') 
          : '#ffffff';
      }

      update() {
        this.y += this.speed;
        if (this.y > canvas.height) {
          this.reset();
        }
      }

      draw() {
        ctx.beginPath();
        ctx.arc(this.x, this.y, this.size, 0, Math.PI * 2);
        ctx.fillStyle = this.color;
        ctx.shadowBlur = this.size > 1.8 ? 5 : 0;
        ctx.shadowColor = this.color;
        ctx.globalAlpha = this.alpha;
        ctx.fill();
        ctx.globalAlpha = 1.0;
        ctx.shadowBlur = 0;
      }
    }

    function initStars() {
      stars = [];
      for (let i = 0; i < maxStars; i++) {
        stars.push(new Star());
      }
    }

    function animate() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      stars.forEach(star => {
        star.update();
        star.draw();
      });
      animationFrameId = requestAnimationFrame(animate);
    }

    window.addEventListener('resize', () => {
      resizeCanvas();
    });

    resizeCanvas();
    initStars();
    animate();
  }

  // --- Copy to Clipboard functionality ---
  const copyBtn = document.getElementById('btn-copy');
  const codeContent = document.getElementById('code-content');

  if (copyBtn && codeContent) {
    copyBtn.addEventListener('click', () => {
      const textToCopy = codeContent.innerText;
      navigator.clipboard.writeText(textToCopy).then(() => {
        copyBtn.classList.add('copied');
        copyBtn.innerHTML = '<i class="fas fa-check"></i>';
        
        setTimeout(() => {
          copyBtn.classList.remove('copied');
          copyBtn.innerHTML = '<i class="far fa-copy"></i>';
        }, 2000);
      }).catch(err => {
        console.error('Failed to copy code: ', err);
      });
    });
  }

  // --- Command Tab Switching ---
  const tabBtns = document.querySelectorAll('.tab-btn');
  if (tabBtns.length > 0 && codeContent) {
    const commandTexts = {
      play: `./gradlew :app:runGame`,
      build: `./gradlew :app:deploy`,
      builder: `./gradlew :builder:run`
    };

    tabBtns.forEach(btn => {
      btn.addEventListener('click', (e) => {
        tabBtns.forEach(b => b.classList.remove('active'));
        e.target.classList.add('active');

        const targetCmd = e.target.getAttribute('data-tab');
        codeContent.innerText = commandTexts[targetCmd];
      });
    });
  }
});
